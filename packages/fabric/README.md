# SharedWorld Mod

SharedWorld Mod is the current player-facing client package for Fabric. It
adds the Shared Worlds flow to Minecraft and coordinates with the SharedWorld
backend for session ownership, sync, invites, and recovery.

## Current Support

- SharedWorld currently builds against Minecraft `1.21.11`
- The validated stable pair is `Minecraft 1.21.11 + e4mc 6.1.0-fabric`
- Older `e4mc` builds such as `6.0.6` are historical reference points, not the
  current support promise
- Future `e4mc` upgrades should be appended to the manifest allowlist only
  after validation

## Build Requirements

- Java 21 JDK
- A Fabric-compatible `e4mc` jar available in `packages/fabric/local/mods/`
- A SharedWorld backend if you want to exercise full end-to-end flows locally

Nested jars inside local runtime mods are automatically extracted into the
development classpath so packaged dependencies inside mods like `e4mc` work in
the generated run configurations.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
GRADLE_USER_HOME=/tmp/sharedworld-gradle \
bash ./gradlew --no-daemon -p packages/fabric remapJar
```

The remapped release jar is written to `packages/fabric/build/libs/`.
