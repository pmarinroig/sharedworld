//! Tokens at rest: Google refresh/access tokens are stored as
//! `enc:v1:<b64url nonce‖ciphertext>` under an AES-256-GCM master key when
//! one is configured (`/etc/sharedworld/master.key`). Plaintext rows (the
//! D1 import) keep working until `swctl encrypt-tokens` converts them.

use std::path::Path;

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;

const PREFIX: &str = "enc:v1:";

pub struct TokenCipher {
    cipher: Aes256Gcm,
}

impl TokenCipher {
    pub fn new(key: [u8; 32]) -> Self {
        Self { cipher: Aes256Gcm::new((&key).into()) }
    }

    /// Key file: 32 raw bytes or a base64 string of 32 bytes.
    pub fn from_key_file(path: &Path) -> std::io::Result<Self> {
        let bytes = std::fs::read(path)?;
        let key: Vec<u8> = if bytes.len() == 32 {
            bytes
        } else {
            let text = String::from_utf8_lossy(&bytes);
            base64::engine::general_purpose::STANDARD.decode(text.trim()).map_err(|e| {
                std::io::Error::new(std::io::ErrorKind::InvalidData, format!("master key: {e}"))
            })?
        };
        let key: [u8; 32] = key.try_into().map_err(|_| {
            std::io::Error::new(std::io::ErrorKind::InvalidData, "master key must be 32 bytes")
        })?;
        Ok(Self::new(key))
    }

    pub fn generate_key_b64() -> String {
        let mut k = [0u8; 32];
        rand::fill(&mut k);
        base64::engine::general_purpose::STANDARD.encode(k)
    }

    pub fn is_encrypted(value: &str) -> bool {
        value.starts_with(PREFIX)
    }

    pub fn encrypt(&self, plaintext: &str) -> String {
        if Self::is_encrypted(plaintext) {
            return plaintext.to_string();
        }
        let mut nonce = [0u8; 12];
        rand::fill(&mut nonce);
        let nonce_arr = Nonce::try_from(&nonce[..]).expect("12-byte nonce");
        let ct = self.cipher.encrypt(&nonce_arr, plaintext.as_bytes()).expect("aes-gcm");
        let mut out = nonce.to_vec();
        out.extend_from_slice(&ct);
        format!("{PREFIX}{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(out))
    }

    /// Decrypts `enc:v1:` values; passes plaintext through; `None` when an
    /// encrypted value cannot be opened (wrong key); treated as absent.
    pub fn decrypt(&self, stored: &str) -> Option<String> {
        let Some(b64) = stored.strip_prefix(PREFIX) else { return Some(stored.to_string()) };
        let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(b64).ok()?;
        if bytes.len() < 12 {
            return None;
        }
        let nonce = Nonce::try_from(&bytes[..12]).ok()?;
        let plain = self.cipher.decrypt(&nonce, &bytes[12..]).ok()?;
        String::from_utf8(plain).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_and_passthrough() {
        let c = TokenCipher::new([7u8; 32]);
        let enc = c.encrypt("1//refresh");
        assert!(enc.starts_with("enc:v1:"));
        assert_eq!(c.decrypt(&enc).as_deref(), Some("1//refresh"));
        assert_eq!(c.decrypt("plain").as_deref(), Some("plain"));
        assert_eq!(c.encrypt(&enc), enc, "already encrypted is left alone");
        let other = TokenCipher::new([8u8; 32]);
        assert!(other.decrypt(&enc).is_none());
    }
}
