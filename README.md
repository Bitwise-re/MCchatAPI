# MC Chat API

> [!TIP]
> Looking to build, run, or contribute to the mod? Developers should also read [DEVS.md](DEVS.md).

MC Chat API is a **server-side** Fabric mod that connects your Minecraft chat to an external service — for example a web livechat or a bot — through a local Unix domain socket. Players install nothing, and no network port is opened.

## What it does

- **Minecraft → your service:** public chat messages, chat commands (such as `/say`), and in-game system messages are broadcast to every connected service as they happen.
- **Your service → Minecraft:** messages sent by a connected service appear to all players as in-game system messages.
- Several services can share the same socket at once; each connection is handled independently.

## Requirements

| Requirement | Details |
| --- | --- |
| Minecraft | 26.1.2 or newer |
| Loader | Fabric (server side only) |
| Fabric API | Required |
| Java | 25 or newer |
| Host | Linux/Unix — the mod uses Unix domain sockets; Windows is not supported |

## Installation

1. Put the mod jar and Fabric API in your server's `mods/` folder.
2. Start the server once. The mod creates `config/mcchatapi.conf` with default settings and starts listening on its socket when the server finishes starting.
3. Restart the server after changing the config.

## Configuration

The config file is `config/mcchatapi.conf` (JSON):

| Setting | Default | Description |
| --- | --- | --- |
| `socket-path` | `mcchatapi-sockets/mcchat.sock` | Path of the Unix socket. Relative paths are resolved from the server directory; absolute paths also work. |
| `socket-access-group` | `mcchatapi` | Unix group allowed to use the socket in addition to the server's own user. |

```json
{
  "socket-path": "mcchatapi-sockets/mcchat.sock",
  "socket-access-group": "mcchatapi"
}
```

If the file is missing or invalid, the defaults are used and a warning is logged.

## Connecting your service

The socket speaks newline-terminated JSON in UTF-8: one packet per line.

| Direction | Packet | Meaning |
| --- | --- | --- |
| Service → mod | `{"type":"hello"}` | Handshake; the mod replies with `{"type":"ack"}`. |
| Service → mod | `{"type":"Wmsg","msg":"Hello from the web"}` | Show a message to all players in-game. |
| Mod → service | `{"type":"Mmsg","msg":"..."}` | A Minecraft chat message, chat command, or system message. |

A typical exchange looks like this:

```json
{"type":"hello"}
{"type":"ack"}
{"type":"Wmsg","msg":"Hello from the web"}
{"type":"Mmsg","msg":"<Player> hi there"}
```

The first two lines are the handshake; the next two are examples of messages in each direction. Unknown packet types or missing `msg` fields close the connection with a warning in the log.

## Permissions and security

The socket is never world-readable. By default only the user running the Minecraft server can use it. One additional Unix group can be granted access through file ACLs.

To let a web service connect:

1. Create the group if needed: `sudo groupadd mcchatapi`
2. Add the service user to it: `sudo usermod -aG mcchatapi www-data`
3. Set `socket-access-group` in the config to that group name, then restart the server.
4. Make sure `getent` and `setfacl` are installed (`acl` package on most distributions).

When group access is enabled, the mod sets ACLs on the socket's parent folder so the group can traverse it and use sockets created inside. If the group does not exist, the ACL tools are unavailable, or setting ACLs fails, the mod logs a warning and falls back to **owner-only** access (mode `600`) — only the server user can connect. The socket file is removed when the server stops.

> **Caution:** Do not put the socket in `/run` or another `tmpfs` location unless you are sure it survives a reboot and that the web service can still reach it after the server restarts.

## Troubleshooting

| Log message | What to do |
| --- | --- |
| `OS not supported` | The server is running on Windows. Run it on a Linux/Unix host; the mod stays inactive and creates no config file or socket. |
| `mod disabled : could not open socket` | Check that the socket path's parent directory can be created and written to by the server user. Chat broadcasting stays off until the server is restarted with a working path. |
| `Socket access group '...' does not exist` | Create the group or correct `socket-access-group`; the socket is owner-only until then. |
| `Could not set ACL ... using owner-only permissions` | Install `setfacl` (the `acl` package) and check that the filesystem supports ACLs. |
| `Closing client after protocol error` | The connected service sent malformed JSON or an unknown `type`; fix the packets it sends. |
| `Listening on ...` | Normal startup message showing the socket path in use. |

## Links

- [Source code and issue tracker](https://github.com/Bitwise-re/MCchatAPI)
