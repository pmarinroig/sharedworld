/**
 * Worker environment (lane-D only; the lane-D-specific keys live on
 * `LaneDEnv` in lane-d.ts). Every key here must have a matching field on the
 * box's Config — enforced by
 * `server/crates/sw-http/tests/parity.rs::every_worker_env_key_has_a_config_field`.
 */
export interface Env {
  GOOGLE_DRIVE_API_BASE?: string;
}
