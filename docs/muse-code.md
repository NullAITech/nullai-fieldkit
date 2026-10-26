# Meta Muse Code (`muse` CLI / Muse-Code AppImage)

## How Muse connects (zoth-pill)

| What | Value |
|---|---|
| MCP URL (local, no tunnel) | `http://127.0.0.1:8765/mcp` (streamable HTTP) |
| Auth | `Authorization: Bearer <muse token>` |
| Token | the `muse` line in `~/.config/zoth-relay/agent-tokens.txt` (mode 0600, Neal's account). Never paste it into chat, git or logs. |
| Agent / profile | `muse` / **`dev`** |
| Workspace | `/home/zoth-relay/agent-workspace/muse` |

Muse Code 1.4.6 has **no `muse mcp add`**. Declare the server in
`${XDG_CONFIG_HOME:-~/.config}/muse/settings.json` (or a project `.mcp.json`), key `mcpServers`
(shape from the docs embedded in the Muse binary; not run end-to-end because that needs a Muse login):

```json
{
  "schema_version": 1,
  "mcpServers": {
    "zoth-relay": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <muse token from agent-tokens.txt>" }
    }
  }
}
```

Keep `settings.json` at mode 0600 because it holds the token. Don't run `muse mcp login zoth-relay`
(that's for OAuth servers). Prefer the HTTP URL over `mcp-stdio`: stdio runs the relay as *your*
user, outside the systemd sandbox. A remote URL needs a tunnel, which is off until Neal enables it.

## The `dev` profile (what Muse may do)

`run_command` runs as the non-root `zoth-relay` user inside the systemd hardening **and** a
Landlock kernel sandbox (`zoth_relay/landlock.py`) applied per command:

* **Read/write/exec only** inside its workspace. cwd is locked there. Other agents' dirs, the relay's
  home, `/var/log/zoth-relay`, `/etc/zoth-relay`, `/etc/shadow` and `/home/*` are denied.
  System dirs (`/usr`, `/opt`, `/etc`, ...) are read-only.
* **Private HOME-like env** inside the workspace: `HOME=.home`, `TMPDIR=.tmp`, `XDG_*`,
  `npm_config_cache=.cache/npm`, `npm_config_prefix=.home/.npm-global`, `PIP_CACHE_DIR=.cache/pip`,
  `PIP_REQUIRE_VIRTUALENV=true` (use `python3 -m venv .venv`; `.venv/bin` and `node_modules/.bin` are on PATH),
  `CARGO_HOME`, `GIT_CONFIG_GLOBAL=.home/.gitconfig`.
* **Tools**: git, node/npm/npx (pnpm via `npm i -g pnpm` into the private prefix), python3/pip in a venv,
  make, gcc, cargo, curl/wget. Outbound network (package registries) is allowed.
* **Dev servers**: TCP bind only to **127.0.0.1:4100-4199** (`dev_ports`; `HOST=127.0.0.1`, `PORT=4100` are
  preset). Binding other ports is refused by the kernel. Commands that ask for `0.0.0.0`/`::` are refused by
  the relay, and ufw's default-deny-incoming keeps the range off the LAN anyway.
* **No sudo/su/pkexec** (blocklist + NoNewPrivileges), no signalling processes outside its own sandbox.
* **Limits**: default timeout 300 s, max 900 s, 20 MB output per command, 300 requests/min.

Background servers: start them with `nohup ... > srv.log 2>&1 & echo $! > srv.pid` and stop them with
`kill $(cat srv.pid)`.

**Meta Muse (Mac desktop app)** has no MCP setting that I could verify. Use the REST one-liners from
`generic-agent.md`, or point it at the tunnel if it can run shell commands.
