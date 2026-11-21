# MC Chat API — developer guide

Server-sided mod offering an API to use the in-game chat. Created for a
[bitwise.re](https://www.bitwise.re) [feature](https://github.com/Bitwise-re/rpi_apache/issues/19).

User-facing documentation (installation, config, protocol for service authors) lives in
[README.md](README.md). Keep both documents in sync when the config or wire protocol changes.

## Development environment

The project targets **Minecraft 26.1.2** with **Fabric**. Minecraft 26.1 and later are
unobfuscated, so no Yarn or Mojang mappings dependency is used.

| Tool | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.155.3+26.1.2 |
| Fabric Loom | 1.19.2 |
| Gradle | 9.8.2 |
| Java | 25 |

### Requirements

- JDK 25
- Git
- GNU Make (optional, for the shortcut targets)

On Arch Linux:

```sh
sudo pacman -S --needed jdk25-openjdk
```

The build declares a Java 25 toolchain. The `Makefile` points `JAVA_HOME` at
`/usr/lib/jvm/java-25-openjdk` when it is installed, so Gradle and the dev server run
on JDK 25 without changing the system-wide default Java.

### Commands

```sh
make build          # build the mod jar
make run            # run a local development server with info logs
make run DEBUG=1    # run the dev server with debug logs
make debug          # shortcut for make run DEBUG=1
make sources        # decompile Minecraft sources for reference
make clean          # delete Gradle build output
make doctor         # show the active Java and Gradle versions
make smoke          # build, start an isolated server, and run the socket smoke test
```

Without Make:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew runServer
```

The built jar is written to `build/libs/mcchatapi-<version>+<minecraft-version>.jar`
(for example `mcchatapi-0.1.0+26.1.2.jar`).

The local development server uses the `run/` directory. It is ignored by git and
`make run` creates `run/eula.txt` and `run/server.properties` (`eula=true`,
`online-mode=false`) on first use. Logs default to `info`; `make run DEBUG=1`
(or `make debug`) sets the Minecraft log level to `debug`.

## Code map

| File | Responsibility |
| --- | --- |
| `MCChatAPI.java` | Mod initializer. Loads the config, registers Fabric lifecycle/chat events, and starts/stops the socket server with the Minecraft server. |
| `ModConfig.java` | Reads or creates `config/mcchatapi.conf` (relative to the game directory) and holds `socket-path` / `socket-access-group`. Invalid files fall back to defaults with a warning. |
| `ChatSocketServer.java` | Owns the listener thread and the set of connected clients; broadcasts Minecraft messages and injects service messages back into the server. |
| `APISocket.java` | Creates the Unix domain socket, its parent directory, and its permissions/ACLs; deletes the socket on shutdown. |
| `ClientConnection.java` | One reader and one writer virtual thread per client, with an outbound queue so chat broadcasts never block on a slow client. |
| `Protocol.java` | Parses/validates incoming packets and formats outgoing packets; all wire-format strings live here. |

### Runtime flow

1. `MCChatAPI.onInitialize()` checks `os.name` and returns immediately with an
   `OS not supported` error on Windows: no config file, callbacks, or socket are created.
2. It then loads `ModConfig` from the game directory.
3. `ServerLifecycleEvents.SERVER_STARTED` creates `ChatSocketServer`; `SERVER_STOPPING`
   closes it. If socket setup fails, the mod logs `mod disabled : could not open socket`
   and chat broadcasting stays off until the next server start.
4. `ServerMessageEvents` (`CHAT_MESSAGE`, `COMMAND_MESSAGE`, non-overlay `GAME_MESSAGE`)
   broadcast decorated text to every connected client as an `Mmsg` packet.
5. Incoming `Wmsg` packets are posted with `server.executeIfPossible(...)` and shown to
   all players via `broadcastSystemMessage(Component.literal(msg), false)`.
6. A malformed packet or unknown `type` closes that client connection only; other
   clients keep running.

### Socket and permissions

- Relative `socket-path` values resolve from the game directory, so the dev server uses
  `run/mcchatapi-sockets/mcchat.sock` by default. Check the `Listening on ...` log line
  for the exact path.
- The mod creates the parent directory and socket itself. A configured access group is
  checked with `getent group`, then `setfacl` grants the group traverse rights on the
  folder and read/write on the socket. Tools are given 5 seconds to finish.
- If the group is missing, `getent`/`setfacl` are unavailable, or ACL setup fails, the
  mod warns and falls back to owner-only mode (`600`). The socket is never world-readable.
- Avoid `/run` and other `tmpfs` locations for persistent setups: the socket disappears
  on reboot, and clients must reconnect after a server restart.

### Wire protocol

Newline-terminated UTF-8 JSON, one packet per line:

| Direction | Packet | Meaning |
| --- | --- | --- |
| Client → mod | `{"type":"hello"}` | Handshake; the mod replies with `{"type":"ack"}`. |
| Client → mod | `{"type":"Wmsg","msg":"text"}` | Show `text` to all players as a system message. |
| Mod → client | `{"type":"Mmsg","msg":"text"}` | A Minecraft chat message, command, or system message. |

Where present, `type` and `msg` must be JSON strings. `Protocol.handlePacket` is the single place
to change when extending the protocol; update the table in `README.md` at the same time.

## Manual smoke test

You need `socat` (or another Unix-socket client). Start the dev server, watch for the
`Listening on ...` line, then connect from another terminal:

```sh
socat - UNIX-CONNECT:run/mcchatapi-sockets/mcchat.sock
```

In the `socat` session:

```json
{"type":"hello"}
{"type":"Wmsg","msg":"smoke test from dev"}
```

Expect an `ack` packet back and the message to appear in the dev server/game. Then send a
chat message in-game or from the server console and expect an `Mmsg` line in `socat`.
Run `make run DEBUG=1` for connection/disconnection and shutdown logs.

## CI and releases

GitHub Actions runs `.github/workflows/build-release.yml` on every branch push. The
`build` job compiles the mod and sources jars and stores them as a workflow artifact
named after the built mod jar. On `main`, the `release` job runs only after `build`
succeeds and publishes both jars to a GitHub release whose tag and title are the built
mod jar's filename without `.jar` (for example `mcchatapi-0.1.0+26.1.2`). A re-run or
a later push with the same jar name replaces the existing release's assets.

### Pull request smoke test

`.github/workflows/smoke-test.yml` runs on pull request `opened`, `synchronize`,
`reopened`, and `ready_for_review` events. It builds the mod, starts a real Fabric
server with RCON enabled, and uses `scripts/smoke-test.py` to:

- wait for the `Listening on ...` log line,
- complete the `hello` / `ack` handshake,
- send a `Wmsg` and check that the injected text appears in the server log,
- issue `say` over RCON and check that an `Mmsg` packet reaches the socket client.

The test config uses an empty access group so CI does not depend on `getent`/`setfacl`;
owner-only socket permissions are enough. On failure, the server log is uploaded as the
`smoke-server-log` artifact.

Locally, run the same test with `make smoke`. It uses `scripts/run-smoke.sh`, builds the
mod, starts an isolated server in `smoke-run/` (leaving your normal `run/` untouched),
writes `smoke-server.log`, and shuts the server down when finished. Override the
defaults with `SMOKE_RUN_DIR`, `SMOKE_LOG`, `SMOKE_RCON_PORT`, and
`SMOKE_RCON_PASSWORD` if needed.

### Windows init guard

The `windows-init-check` job in the same workflow runs on `windows-latest` to verify
graceful degradation on an unsupported host (it does not make Windows a supported
platform). It builds with `.\gradlew.bat`, then `scripts/check-windows-init.ps1` starts
an isolated server in `windows-smoke-run/` and checks that:

- `OS not supported` appears in `windows-smoke-server.log`,
- the server still reaches `Done (...)` and stays up,
- `Listening on` never appears (no socket is opened),
- no `config/mcchatapi.conf` and no `mcchatapi-sockets/` are created in the run dir.

The server is stopped over RCON with a process-tree kill as a fallback, and the log is
uploaded as the `windows-init-server-log` artifact on failure.
