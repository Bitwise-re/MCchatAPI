# MC Chat API

- Mod ID: `mcchatapi`
- Group: `dev.bitwise.mcchatapi`
- Minecraft: `26.1.2`
- Loader: Fabric `0.20.1`
- Fabric API: `0.155.3+26.1.2`
- Loom: `1.19.2`
- Gradle: `9.8.2`
- Java: `25`

## Project structure

- `src/main/java/dev/bitwise/mcchatapi/` — mod entrypoint and future implementation
- `src/main/resources/fabric.mod.json` — Fabric metadata
- `run/` — local development server directory (ignored by git)

This is currently an environment scaffold only. The chat/API features have not been
implemented yet.

## Build

```sh
make build
# or
./gradlew build
```

The output jar is `build/libs/mcchatapi-<version>.jar`.

## Development server

```sh
make run
# or
./gradlew runServer
```

The server runs in `run/` with `--nogui`. `run/eula.txt` and
`run/server.properties` are local development files and are not committed;
`make run` creates them from `make setup-run` when missing.

## Minecraft 26.1+ notes

- Minecraft 26.1+ is unobfuscated; there is no `mappings` dependency.
- Java 25 is required; the build uses a Java 25 toolchain.
- Use the names present in the game jar (for example `net.minecraft.resources.Identifier`).
- Prefer Fabric API events over mixins where possible.
- `ServerMessageEvents` is available through the full Fabric API dependency.
