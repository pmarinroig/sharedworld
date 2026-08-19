//! Approximation of JS `String.prototype.localeCompare` (ICU root
//! collation) for the ASCII alphabet used by pack ids and storage keys:
//! punctuation < digits < letters, letters case-insensitive at the primary
//! level with lowercase first as the tie-break. Used wherever the worker
//! sorted with `localeCompare` so directories and manifest documents keep
//! the same order.

use std::cmp::Ordering;

fn primary_weight(c: char) -> (u8, u32) {
    // ICU root order of common ASCII punctuation (subset, in order).
    const PUNCT: &str = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";
    if c.is_ascii_whitespace() {
        return (0, c as u32);
    }
    if let Some(pos) = PUNCT.find(c) {
        return (1, pos as u32);
    }
    if c.is_ascii_digit() {
        return (2, c as u32);
    }
    if c.is_ascii_alphabetic() {
        return (3, c.to_ascii_lowercase() as u32);
    }
    (4, c as u32)
}

pub fn locale_compare(a: &str, b: &str) -> Ordering {
    let mut ai = a.chars();
    let mut bi = b.chars();
    loop {
        match (ai.next(), bi.next()) {
            (None, None) => break,
            (None, Some(_)) => return Ordering::Less,
            (Some(_), None) => return Ordering::Greater,
            (Some(x), Some(y)) => {
                let o = primary_weight(x).cmp(&primary_weight(y));
                if o != Ordering::Equal {
                    return o;
                }
            }
        }
    }
    // Tertiary: lowercase before uppercase at the first case difference.
    for (x, y) in a.chars().zip(b.chars()) {
        if x != y {
            return match (x.is_ascii_lowercase(), y.is_ascii_lowercase()) {
                (true, false) => Ordering::Less,
                (false, true) => Ordering::Greater,
                _ => x.cmp(&y),
            };
        }
    }
    a.len().cmp(&b.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn orders_like_icu_for_pack_ids() {
        let mut ids = vec![
            "region-bundle:superpack:0",
            "non-region",
            "region-bundle:region:-1:1",
            "region-bundle:region:1:-1",
            "region-bundle:DIM-1/region:1:1",
            "region-bundle:DIM1/region:1:1",
        ];
        ids.sort_by(|a, b| locale_compare(a, b));
        assert_eq!(
            ids,
            vec![
                "non-region",
                "region-bundle:DIM-1/region:1:1",
                "region-bundle:DIM1/region:1:1",
                "region-bundle:region:-1:1",
                "region-bundle:region:1:-1",
                "region-bundle:superpack:0",
            ]
        );
        assert_eq!(locale_compare("a", "B"), Ordering::Less);
        assert_eq!(locale_compare("a", "A"), Ordering::Less);
    }
}
