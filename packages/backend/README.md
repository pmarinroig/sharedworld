# SharedWorld Backend

SharedWorld Backend is a Cloudflare Worker service responsible for session
coordination, world metadata, ownership handoff, and storage integration.

## Runtime Pieces

- Cloudflare Workers for the HTTP runtime
- Cloudflare D1 for metadata and coordination state
- Google Drive app data storage for world snapshot persistence

## Key Files

- `wrangler.toml`: Worker configuration and bindings
- `migrations/`: D1 schema migrations
- `.dev.vars.example`: local development variables template

## Local Run

```bash
cp .dev.vars.example .dev.vars
XDG_CONFIG_HOME=/tmp/sharedworld-xdg npx wrangler dev --ip 127.0.0.1 --port 8787
```

## Deploy

```bash
XDG_CONFIG_HOME=/tmp/sharedworld-xdg npx wrangler deploy
```

## Apply Remote Migrations

```bash
XDG_CONFIG_HOME=/tmp/sharedworld-xdg npx wrangler d1 migrations apply sharedworld --remote
```
