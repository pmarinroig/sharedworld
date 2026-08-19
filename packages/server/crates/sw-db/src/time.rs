//! JS-compatible timestamps. Every timestamp the backend stores or emits is
//! `Date.prototype.toISOString()` shaped (`YYYY-MM-DDTHH:MM:SS.mmmZ`) and
//! they are compared lexically in SQL, so the exact shape matters.

use chrono::{DateTime, SecondsFormat, TimeZone, Utc};

pub type Instant = DateTime<Utc>;

pub fn now() -> Instant {
    Utc::now()
}

/// `Date.prototype.toISOString()`.
pub fn to_iso(t: Instant) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub fn now_iso() -> String {
    to_iso(now())
}

pub fn from_millis(ms: i64) -> Instant {
    Utc.timestamp_millis_opt(ms).single().unwrap_or_else(|| Utc.timestamp_millis_opt(0).unwrap())
}

pub fn to_millis(t: Instant) -> i64 {
    t.timestamp_millis()
}

/// `Date.parse` for the shapes we produce (RFC 3339). Returns `None` where
/// JS would return `NaN`.
pub fn parse_iso(s: &str) -> Option<Instant> {
    DateTime::parse_from_rfc3339(s).ok().map(|d| d.with_timezone(&Utc)).or_else(|| {
        // JS also accepts date-only / no-millis forms; be lenient the same way.
        chrono::NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M:%S").ok().map(|n| Utc.from_utc_datetime(&n))
    })
}

/// `new Date(t + ms).toISOString()`.
pub fn plus_ms_iso(t: Instant, ms: i64) -> String {
    to_iso(t + chrono::Duration::milliseconds(ms))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iso_shape_matches_js() {
        let t = from_millis(1_700_000_000_123);
        assert_eq!(to_iso(t), "2023-11-14T22:13:20.123Z");
        assert_eq!(to_iso(from_millis(0)), "1970-01-01T00:00:00.000Z");
        assert_eq!(parse_iso("2023-11-14T22:13:20.123Z").map(to_millis), Some(1_700_000_000_123));
        assert_eq!(plus_ms_iso(t, 1000), "2023-11-14T22:13:21.123Z");
    }
}
