//! `ids.ts`: opaque ids, invite codes, slugs.

use rand::RngExt;

pub fn random_id(prefix: &str) -> String {
    format!("{prefix}_{}", uuid::Uuid::new_v4().simple())
}

pub fn random_server_id() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}

const INVITE_ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/// `XXXX-XXXX-XXXX` from a Crockford-ish alphabet.
pub fn invite_code() -> String {
    let mut rng = rand::rng();
    let mut out = String::with_capacity(14);
    for group in 0..3 {
        if group > 0 {
            out.push('-');
        }
        for _ in 0..4 {
            out.push(INVITE_ALPHABET[rng.random_range(0..INVITE_ALPHABET.len())] as char);
        }
    }
    out
}

/// `slugify`: trim, lowercase, non-[a-z0-9] runs → `-`, strip edge dashes, first 48 chars.
pub fn slugify(name: &str) -> String {
    let lower = name.trim().to_lowercase();
    let mut out = String::new();
    let mut in_run = false;
    for ch in lower.chars() {
        if ch.is_ascii_lowercase() || ch.is_ascii_digit() {
            out.push(ch);
            in_run = false;
        } else if !in_run {
            out.push('-');
            in_run = true;
        }
    }
    let trimmed = out.trim_matches('-');
    trimmed.chars().take(48).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugify_matches_ts() {
        assert_eq!(slugify("  My World!! 2 "), "my-world-2");
        assert_eq!(slugify("---"), "");
        assert_eq!(invite_code().len(), 14);
        assert!(random_id("world").starts_with("world_"));
    }
}
