# SharedWorld

Keep a Minecraft world going with friends without running a dedicated server.

## Install

SharedWorld currently supports Fabric on Minecraft `1.21.11`.

To use it, install:

- SharedWorld
- Fabric API
- `e4mc 6.1.0`

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
[`https://pmarinroig.github.io/sharedworld/`](https://pmarinroig.github.io/sharedworld/).
The privacy policy lives at
[`https://pmarinroig.github.io/sharedworld/privacy/`](https://pmarinroig.github.io/sharedworld/privacy/)
and explains how SharedWorld uses Google Drive app data and session data.

The GitHub Pages source for the public site lives under [`pages/`](./pages/).

## Contributing

Contributions are welcome.

## License

[MIT](./LICENSE)
