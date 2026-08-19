//! Edge ↔ core WebSocket multiplexing protocol over a Unix socket.
//!
//! The edge owns client sockets; the core owns the gateway/coordinator state.
//! One long-lived connection carries every socket's frames, length-delimited
//! and `postcard`-encoded. Per-player FIFO order is preserved because one
//! pipe carries all sends in order. Sends are lossy by contract (the realtime
//! protocol tolerates lost frames); what must not be lost is *state*, which
//! the edge replays on reconnect via [`EdgeToCore::WsAttach`].

use bytes::{Bytes, BytesMut};
use serde::{Deserialize, Serialize};
use tokio_util::codec::{Decoder, Encoder, LengthDelimitedCodec};

pub const PROTOCOL_VERSION: u32 = 1;
/// Frames larger than this are a bug (client text frames are tiny JSON).
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;

pub type ConnId = u64;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum EdgeToCore {
    Hello {
        protocol: u32,
        edge_version: String,
    },
    /// A new client upgrade is pending; the core authenticates and answers
    /// `WsAccept`/`WsReject` before the edge completes the handshake.
    WsOpen {
        conn_id: ConnId,
        authorization: Option<String>,
        peer: String,
    },
    /// Replay of a socket the edge already holds (core restarted): the
    /// original auth header, timing, and the latest retained client frames
    /// (newest `world-presence` per world incl. `present:false`, newest
    /// `host-players` per world).
    WsAttach {
        conn_id: ConnId,
        authorization: Option<String>,
        peer: String,
        connected_at_ms: i64,
        last_seen_ms: i64,
        retained: Vec<String>,
    },
    /// Sent after every `WsAttach` of a reconnect; the core answers
    /// `ReplayAck` once they are applied, and only then does the edge start
    /// forwarding HTTP again (so no event can be published into a gap).
    ReplayDone {
        count: u32,
    },
    WsText {
        conn_id: ConnId,
        text: String,
    },
    /// Keepalive/activity seen on the socket (batched, ≤ 1 per 5 s per conn).
    WsSeen {
        conn_id: ConnId,
        at_ms: i64,
    },
    WsClosed {
        conn_id: ConnId,
        code: Option<u16>,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum CoreToEdge {
    HelloAck { protocol: u32, core_version: String },
    ReplayAck { count: u32 },
    WsAccept { conn_id: ConnId },
    WsReject { conn_id: ConnId, status: u16, body: String },
    WsSend { conn_id: ConnId, text: String },
    WsClose { conn_id: ConnId, code: Option<u16> },
}

#[derive(Debug, thiserror::Error)]
pub enum CodecError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("encode: {0}")]
    Encode(postcard::Error),
    #[error("decode: {0}")]
    Decode(postcard::Error),
}

/// Length-delimited postcard codec for either direction.
pub struct IpcCodec<T> {
    inner: LengthDelimitedCodec,
    _t: std::marker::PhantomData<T>,
}

impl<T> Default for IpcCodec<T> {
    fn default() -> Self {
        Self {
            inner: LengthDelimitedCodec::builder()
                .max_frame_length(MAX_FRAME_BYTES)
                .length_field_length(4)
                .new_codec(),
            _t: std::marker::PhantomData,
        }
    }
}

impl<T: Serialize> Encoder<T> for IpcCodec<T> {
    type Error = CodecError;
    fn encode(&mut self, item: T, dst: &mut BytesMut) -> Result<(), Self::Error> {
        let bytes = postcard::to_allocvec(&item).map_err(CodecError::Encode)?;
        self.inner.encode(Bytes::from(bytes), dst)?;
        Ok(())
    }
}

impl<T: for<'de> Deserialize<'de>> Decoder for IpcCodec<T> {
    type Item = T;
    type Error = CodecError;
    fn decode(&mut self, src: &mut BytesMut) -> Result<Option<Self::Item>, Self::Error> {
        match self.inner.decode(src)? {
            Some(frame) => Ok(Some(postcard::from_bytes(&frame).map_err(CodecError::Decode)?)),
            None => Ok(None),
        }
    }
}

/// Default socket paths.
pub const DEFAULT_CORE_HTTP_SOCKET: &str = "/run/sharedworld/core-http.sock";
pub const DEFAULT_CORE_WS_SOCKET: &str = "/run/sharedworld/core-ws.sock";

/// `process_resident_memory_bytes` / `process_open_fds` / `process_cpu_seconds_total`, sampled every 10 s
/// (Linux: /proc/self; elsewhere the gauges stay absent). Shared by both
/// binaries so "is memory growing with sockets?" is answerable per process.
pub fn spawn_process_metrics() {
    tokio::spawn(async {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(10));
        loop {
            tick.tick().await;
            if let Some(rss) = resident_memory_bytes() {
                metrics::gauge!("process_resident_memory_bytes").set(rss as f64);
            }
            if let Some(fds) = open_fds() {
                metrics::gauge!("process_open_fds").set(fds as f64);
            }
            if let Some(cpu) = cpu_seconds() {
                metrics::gauge!("process_cpu_seconds_total").set(cpu);
            }
        }
    });
}

fn resident_memory_bytes() -> Option<u64> {
    let statm = std::fs::read_to_string("/proc/self/statm").ok()?;
    let pages: u64 = statm.split_whitespace().nth(1)?.parse().ok()?;
    Some(pages * 4096)
}

/// utime + stime from /proc/self/stat (fields 14 and 15, after the
/// parenthesised command name), in seconds at the usual 100 Hz tick.
fn cpu_seconds() -> Option<f64> {
    let stat = std::fs::read_to_string("/proc/self/stat").ok()?;
    let rest = &stat[stat.rfind(')')? + 2..];
    let fields: Vec<&str> = rest.split_whitespace().collect();
    let utime: f64 = fields.get(11)?.parse().ok()?;
    let stime: f64 = fields.get(12)?.parse().ok()?;
    Some((utime + stime) / 100.0)
}

fn open_fds() -> Option<u64> {
    Some(std::fs::read_dir("/proc/self/fd").ok()?.count() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let mut enc = IpcCodec::<EdgeToCore>::default();
        let mut buf = BytesMut::new();
        enc.encode(EdgeToCore::WsText { conn_id: 7, text: "hi".into() }, &mut buf).unwrap();
        enc.encode(EdgeToCore::WsSeen { conn_id: 7, at_ms: 5 }, &mut buf).unwrap();
        let mut dec = IpcCodec::<EdgeToCore>::default();
        assert_eq!(dec.decode(&mut buf).unwrap(), Some(EdgeToCore::WsText { conn_id: 7, text: "hi".into() }));
        assert_eq!(dec.decode(&mut buf).unwrap(), Some(EdgeToCore::WsSeen { conn_id: 7, at_ms: 5 }));
        assert_eq!(dec.decode(&mut buf).unwrap(), None);
    }
}
