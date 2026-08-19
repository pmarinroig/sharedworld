//! Seeded players and request contexts (`test/support/lifecycle.ts` + `sqlite-d1.ts`).

use sw_core::RequestContext;

pub const OWNER_UUID: &str = "owner-uuid";
pub const GUEST_UUID: &str = "guest-uuid";
pub const HOST_MEMBER_UUID: &str = "host-member-uuid";

/// The 13 pre-seeded test players of the Bun harness.
pub const SEEDED_PLAYERS: &[(&str, &str)] = &[
    ("owner-uuid", "Owner"),
    ("guest-uuid", "Guest"),
    ("host-member-uuid", "HostMember"),
    ("third-uuid", "Third"),
    ("player-owner", "Owner"),
    ("player-guest", "Guest"),
    ("player-host", "Host"),
    ("player-other", "Other"),
    ("player-kicked", "Kicked"),
    ("alice-uuid", "Alice"),
    ("bob-uuid", "Bob"),
    ("carol-uuid", "Carol"),
    ("dave-uuid", "Dave"),
];

pub fn owner() -> RequestContext {
    RequestContext::new(OWNER_UUID, "Owner")
}
pub fn guest() -> RequestContext {
    RequestContext::new(GUEST_UUID, "Guest")
}
pub fn host_member() -> RequestContext {
    RequestContext::new(HOST_MEMBER_UUID, "HostMember")
}
pub fn ctx(uuid: &str, name: &str) -> RequestContext {
    RequestContext::new(uuid, name)
}
/// A context claiming a client version (drives the version gates).
pub fn ctx_v(uuid: &str, name: &str, version: &str) -> RequestContext {
    RequestContext { client_version: Some(version.to_string()), ..RequestContext::new(uuid, name) }
}
