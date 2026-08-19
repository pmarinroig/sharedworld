//! Realtime wire schema (`realtime.ts`, 0.3.0+). One WebSocket per player
//! carries awareness only; every authoritative change stays HTTP.

use serde::{Deserialize, Serialize};

use crate::types::WorldRuntimeStatus;

pub const REALTIME_PROTOCOL_VERSION: i64 = 1;
/// Client keepalive text answered at the edge without waking world logic.
pub const REALTIME_KEEPALIVE_REQUEST: &str = "sw-keepalive";
pub const REALTIME_KEEPALIVE_RESPONSE: &str = "sw-keepalive-ack";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RealtimeEventKind {
    RuntimeChanged,
    PresenceChanged,
    MembershipChanged,
    SettingsChanged,
    WorldChanged,
    WorldDeleted,
    SnapshotChanged,
}

impl RealtimeEventKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::RuntimeChanged => "runtime-changed",
            Self::PresenceChanged => "presence-changed",
            Self::MembershipChanged => "membership-changed",
            Self::SettingsChanged => "settings-changed",
            Self::WorldChanged => "world-changed",
            Self::WorldDeleted => "world-deleted",
            Self::SnapshotChanged => "snapshot-changed",
        }
    }
}

/// A player currently on the hosted Minecraft server, as reported by the host.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomPlayer {
    pub player_uuid: String,
    pub player_name: String,
}

/// One pushed change notification (`runtime` rides along on runtime-changed).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RealtimeEvent {
    pub world_id: String,
    pub kind: RealtimeEventKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime: Option<WorldRuntimeStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub room_players: Option<Vec<RoomPlayer>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
#[allow(clippy::large_enum_variant)]
pub enum RealtimeServerFrameBody {
    Welcome,
    Event { event: RealtimeEvent },
}

/// `{ v, type: "welcome" }` | `{ v, type: "event", event }`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RealtimeServerFrame {
    pub v: i64,
    #[serde(flatten)]
    pub body: RealtimeServerFrameBody,
}

impl RealtimeServerFrame {
    pub fn welcome() -> Self {
        Self { v: REALTIME_PROTOCOL_VERSION, body: RealtimeServerFrameBody::Welcome }
    }
    pub fn event(event: RealtimeEvent) -> Self {
        Self { v: REALTIME_PROTOCOL_VERSION, body: RealtimeServerFrameBody::Event { event } }
    }
}

/// Client → server frames. `v` is carried but never validated (wire parity).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum RealtimeClientFrameBody {
    #[serde(rename_all = "camelCase")]
    HostPlayers { world_id: String, runtime_epoch: i64, players: Vec<RoomPlayer> },
    #[serde(rename_all = "camelCase")]
    WorldPresence { world_id: String, present: bool },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RealtimeClientFrame {
    #[serde(default)]
    pub v: Option<serde_json::Value>,
    #[serde(flatten)]
    pub body: RealtimeClientFrameBody,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_frames_serialize_like_ts() {
        assert_eq!(
            serde_json::to_string(&RealtimeServerFrame::welcome()).unwrap(),
            r#"{"v":1,"type":"welcome"}"#
        );
        let ev = RealtimeEvent {
            world_id: "w1".into(),
            kind: RealtimeEventKind::SnapshotChanged,
            runtime: None,
            room_players: None,
        };
        assert_eq!(
            serde_json::to_string(&RealtimeServerFrame::event(ev)).unwrap(),
            r#"{"v":1,"type":"event","event":{"worldId":"w1","kind":"snapshot-changed"}}"#
        );
    }

    #[test]
    fn client_frames_parse_like_ts() {
        let f: RealtimeClientFrame = serde_json::from_str(
            r#"{"v":1,"type":"host-players","worldId":"w","runtimeEpoch":3,"players":[{"playerUuid":"p","playerName":"n"}]}"#,
        )
        .unwrap();
        match f.body {
            RealtimeClientFrameBody::HostPlayers { world_id, runtime_epoch, players } => {
                assert_eq!(world_id, "w");
                assert_eq!(runtime_epoch, 3);
                assert_eq!(players.len(), 1);
            }
            _ => panic!(),
        }
        let f: RealtimeClientFrame =
            serde_json::from_str(r#"{"type":"world-presence","worldId":"w","present":false}"#).unwrap();
        assert!(matches!(f.body, RealtimeClientFrameBody::WorldPresence { present: false, .. }));
        assert!(serde_json::from_str::<RealtimeClientFrame>(r#"{"v":1,"type":"nope"}"#).is_err());
    }
}
