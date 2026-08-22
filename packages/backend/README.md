# SharedWorld Lane-D Worker

A thin Cloudflare Worker in front of the SharedWorld Rust server (the box,
`packages/server`). It exists so jars released before 0.4.6 — which point at
this worker's URL — keep working, and so blob downloads can be relayed from
Google Drive at the edge instead of consuming the box's bandwidth.

- `src/lane-d.ts`: everything the worker does — forward HTTP + WebSocket
  traffic to `BOX_URL`, and serve blob GETs directly from Drive using
  box-signed relay tokens (Ed25519 signature, AES-GCM-sealed Drive token).
- `src/index.ts`: entry point; refuses to run in any mode but `lane-d`.
- `src/schema.sql` + `migrations/`: **not used by the worker anymore** — they
  are the canonical schema history, compiled into the box by
  `server/crates/sw-db` (do not delete or renumber).

Deploy with `scripts/cf-deploy.sh` (secrets: `INTERNAL_API_SECRET`,
`RELAY_PUBLIC_KEY`, `RELAY_TOKEN_KEY`). Tests: `bun test`.

The full D1 + Durable Objects backend that used to live here was retired at
the 2026-08-19 cutover to the box; git history has it.
