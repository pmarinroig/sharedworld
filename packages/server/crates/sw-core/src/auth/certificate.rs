//! Offline verification of Mojang player certificates (`auth/certificate.ts`).

use base64::Engine;
use rsa::pkcs1v15::{Signature, VerifyingKey};
use rsa::pkcs8::DecodePublicKey;
use rsa::signature::Verifier;
use rsa::RsaPublicKey;

use crate::http_error::HttpError;

/// The exact byte layout Mojang's signature covers: BE UUID msb (8) | lsb
/// (8) | expiry epoch millis (8) | X.509 SPKI DER of the public key.
pub fn build_certificate_signed_payload(
    player_uuid_hex: &str,
    expires_at_epoch_ms: i64,
    public_key_der: &[u8],
) -> Result<Vec<u8>, HttpError> {
    if player_uuid_hex.len() != 32
        || !player_uuid_hex.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
    {
        return Err(HttpError::new(403, "certificate_invalid", "Minecraft profile certificate is invalid."));
    }
    let msb = u64::from_str_radix(&player_uuid_hex[..16], 16).map_err(|_| {
        HttpError::new(403, "certificate_invalid", "Minecraft profile certificate is invalid.")
    })?;
    let lsb = u64::from_str_radix(&player_uuid_hex[16..], 16).map_err(|_| {
        HttpError::new(403, "certificate_invalid", "Minecraft profile certificate is invalid.")
    })?;
    let mut payload = Vec::with_capacity(24 + public_key_der.len());
    payload.extend_from_slice(&msb.to_be_bytes());
    payload.extend_from_slice(&lsb.to_be_bytes());
    payload.extend_from_slice(&expires_at_epoch_ms.to_be_bytes());
    payload.extend_from_slice(public_key_der);
    Ok(payload)
}

/// True when any of the services keys validly signed the payload (SHA1withRSA).
pub fn verify_certificate_signature(
    payload: &[u8],
    key_signature: &[u8],
    services_keys_der: &[Vec<u8>],
) -> bool {
    services_keys_der.iter().any(|der| {
        let Ok(key) = RsaPublicKey::from_public_key_der(der) else { return false };
        let verifier = VerifyingKey::<sha1::Sha1>::new(key);
        let Ok(sig) = Signature::try_from(key_signature) else { return false };
        verifier.verify(payload, &sig).is_ok()
    })
}

/// True when `signature` is the certified key's SHA256withRSA signature over the nonce bytes.
pub fn verify_nonce_signature(public_key_der: &[u8], nonce: &str, signature: &[u8]) -> bool {
    let Ok(key) = RsaPublicKey::from_public_key_der(public_key_der) else { return false };
    let verifier = VerifyingKey::<rsa::sha2::Sha256>::new(key);
    let Ok(sig) = Signature::try_from(signature) else { return false };
    verifier.verify(nonce.as_bytes(), &sig).is_ok()
}

/// `atob`-style decode (standard alphabet, padding tolerant).
pub fn decode_base64_field(
    value: &str,
    code: &'static str,
    message: &str,
    status: u16,
) -> Result<Vec<u8>, HttpError> {
    let cleaned: String = value.chars().filter(|c| !c.is_ascii_whitespace()).collect();
    base64::engine::general_purpose::STANDARD
        .decode(&cleaned)
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(&cleaned))
        .map_err(|_| HttpError::new(status, code, message))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::pkcs1v15::SigningKey;
    use rsa::pkcs8::EncodePublicKey;
    use rsa::signature::{RandomizedSigner, SignatureEncoding};
    use rsa::RsaPrivateKey;

    #[test]
    fn signs_and_verifies_like_vanilla() {
        let mut rng = rand_core_compat::Rng;
        let services = RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let profile = RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let profile_der = profile.to_public_key().to_public_key_der().unwrap().into_vec();
        let uuid = "0123456789abcdef0123456789abcdef";
        let payload = build_certificate_signed_payload(uuid, 1_700_000_000_000, &profile_der).unwrap();
        let key_sig =
            SigningKey::<sha1::Sha1>::new(services.clone()).sign_with_rng(&mut rng, &payload).to_vec();
        let services_der = services.to_public_key().to_public_key_der().unwrap().into_vec();
        assert!(verify_certificate_signature(&payload, &key_sig, &[vec![1, 2, 3], services_der.clone()]));
        assert!(!verify_certificate_signature(&payload, &key_sig, std::slice::from_ref(&profile_der)));
        let nonce_sig =
            SigningKey::<rsa::sha2::Sha256>::new(profile).sign_with_rng(&mut rng, b"nonce-1").to_vec();
        assert!(verify_nonce_signature(&profile_der, "nonce-1", &nonce_sig));
        assert!(!verify_nonce_signature(&profile_der, "nonce-2", &nonce_sig));
        assert!(build_certificate_signed_payload("ABC", 0, &[]).is_err());
    }

    /// rsa 0.9 still uses rand_core 0.6; bridge the workspace's rand via a tiny adapter.
    mod rand_core_compat {
        pub struct Rng;
        impl rsa::rand_core::RngCore for Rng {
            fn next_u32(&mut self) -> u32 {
                rand::random()
            }
            fn next_u64(&mut self) -> u64 {
                rand::random()
            }
            fn fill_bytes(&mut self, dest: &mut [u8]) {
                rand::fill(dest)
            }
            fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rsa::rand_core::Error> {
                rand::fill(dest);
                Ok(())
            }
        }
        impl rsa::rand_core::CryptoRng for Rng {}
    }
}
