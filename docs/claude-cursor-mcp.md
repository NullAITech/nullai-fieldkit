# Claude & Cursor (MCP)

zoth-relay speaks MCP in two ways:
- **Streamable HTTP** at `/mcp` (stateless, bearer auth). Use it for remote clients or the tunnel.
- **stdio**: `zoth-relay mcp-stdio` is a small bridge that forwards to the running service over REST. Commands still run as the hardened `zoth-relay` user. `--direct` runs in-process as *you* and is meant for development only.

Tools: `status`, `list_dir`, `read_file`, `write_file`, `run_command`.

Create a token: `sudo zoth-relay agent add claude --profile exec` (or `cursor`).

## Claude Code
```bash
claude mcp add --transport http zoth-relay https://<tunnel>.trycloudflare.com/mcp \
  --header "Authorization: Bearer <token>"
# same machine, no tunnel:
claude mcp add zoth-relay --env ZOTH_RELAY_TOKEN=<token> -- zoth-relay mcp-stdio
```

## Claude Desktop (`claude_desktop_config.json`)
```json
{ "mcpServers": { "zoth-relay": {
    "command": "zoth-relay", "args": ["mcp-stdio", "--url", "http://127.0.0.1:8765"],
    "env": { "ZOTH_RELAY_TOKEN": "<token>" } } } }
```
Claude.ai custom connectors expect OAuth, and zoth-relay only does bearer tokens. For claude.ai, use Claude Desktop or Claude Code.

## Cursor (`~/.cursor/mcp.json` or `.cursor/mcp.json`)
```json
{ "mcpServers": { "zoth-relay": {
    "url": "https://<tunnel>.trycloudflare.com/mcp",
    "headers": { "Authorization": "Bearer <token>" } } } }
```
For a local stdio setup, use `"command": "zoth-relay", "args": ["mcp-stdio"], "env": {"ZOTH_RELAY_TOKEN": "<token>"}`.

## Quick check
```bash
npx @modelcontextprotocol/inspector   # Transport: Streamable HTTP, URL .../mcp, header Authorization
```
