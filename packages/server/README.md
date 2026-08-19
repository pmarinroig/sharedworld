# SharedWorld server (Rust)

The self-hosted SharedWorld backend: a 1:1 port of the Cloudflare worker in
`../backend` (same routes, bodies, error codes, WebSocket frames, SQL schema and
migrations) running on one box with SQLite. Clients cannot tell the two apart —
the Java integration suite and the two-client e2e run unchanged against either.

```
crates/
  sw-contracts   DTOs/constants transcribed from ../shared/src/{contracts,realtime,sync}.ts
  sw-db          rusqlite pool (single writer thread + readers), migrations, repository (SQL port)
  sw-core        domain: auth, worlds, members, session, snapshots, sync plan, storage (Drive/fs),
                 stamps, relay tokens, realtime (coordinator actors + gateway), jobs, config
  sw-http        axum router, auth extractors, error shapes, metrics middleware, /ws, IPC server,
                 testkit routes (feature `testkit`)
  sw-ipc         edge↔core protocol over Unix sockets (postcard frames) + process metrics
  sw-testkit     fake Google Drive, fixtures, integration-harness state (Bun harness parity)
  swcore         the core binary (HTTP/WS services, SQLite, coordinators, jobs)
  swedge         the edge binary (TLS via ACME, reverse proxy, WebSocket owner across core restarts)
  swctl          operator CLI (migrate, stats, import-d1, import-coordinators, encrypt-tokens, keys)
  sw-loadgen     load generator (N worlds × host + guests at scaled protocol cadences; see docs/box-load-profile.md)
ops/             systemd units, swcore.toml.example, litestream, Grafana compose + dashboard
```

Why two binaries: `swedge` owns the TLS listener and every client socket and is almost
never restarted; `swcore` carries all the logic and restarts in well under a second while
the edge holds the sockets, queues HTTP, and replays the open sockets to the new core.

## Develop

```bash
scripts/box-dev.sh                                        # local server on :8787 (--edge / --through-cf / --release)
scripts/box-check.sh                                      # fmt + clippy -D warnings + tests (from the workspace root)
scripts/test-mod-integration.sh                 # Java integration suite against swcore (default backend)
scripts/e2e/run-two-client-e2e.sh              # two real Minecraft clients against swcore (default backend)
scripts/manual-two-client.sh                   # two visible clients for a manual playtest (Rust backend by default)
BOX=deploy@host scripts/box-ops.sh top                              # remote ops over ssh (status/logs/migrate/stats/top)
```

Docs live in the workspace repo: `docs/deploy-box.md`, `docs/cutover-runbook.md`,
`docs/box-observability.md`; the protocol docs (`docs/protocol.md`, `docs/realtime.md`,
`docs/sync.md`) apply verbatim.

## Conventions

- Every statement in `sw-db` has a static name; rows/steps/duration are recorded per
  statement and attributed to the HTTP route (`db_route_*` metrics).
- Worker migrations (`../backend/migrations/0001…`) are applied verbatim; box-only
  migrations live in `crates/sw-db/migrations/` starting at `0030`.
- Response shapes are owned by `sw-contracts`; anything typed loosely on the TS side stays
  `serde_json::Value` and is validated by the service exactly where the worker validates it.
- The TS name of each ported function appears in the Rust doc comment, so a diff against
  the worker is a grep away.
