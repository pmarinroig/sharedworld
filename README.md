# SharedWorld

Keep a Minecraft world going with friends without running a dedicated server.

## Install

SharedWorld currently supports Fabric on Minecraft `1.20`-`1.20.1`, `1.21`-`1.21.11`, and `26.1`-`26.2`.

To use it, install:

- SharedWorld
- Fabric API
- `e4mc 6.2.1` (Minecraft 26.x needs the `-fabric-modern` variant)

If you create a SharedWorld, you will also need to link Google Drive so the mod
can store that world's backups and handoff data in the app data folder.
The mod only has access to its own app data folder, not your entire drive.

## Usage

Create or open your world, then use the SharedWorld screen in-game to turn it
into a shared world.

When one player leaves, another player can take over hosting and keep the same
world going. Friends connect through `e4mc`, so the active host can play from
their own client instead of keeping a dedicated server online.

The backend is public and can be self-hosted.

## Privacy

The public site is published at
[`https://sharedworld.net`](https://sharedworld.net)

The privacy policy lives at
[`https://sharedworld.net/privacy`](https://sharedworld.net/privacy)
and explains how SharedWorld uses Google Drive app data and session data.

The terms of service live at
[`https://sharedworld.net/terms`](https://sharedworld.net/terms)

## Contributing

Contributions are welcome.

## License

[MIT](./LICENSE)
