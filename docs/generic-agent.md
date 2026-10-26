# Any other agent (dots, jev, custom bots, scripts)

I don't know the internals of tools like "dots" or "jev", so this guide treats them as generic clients. Pick whichever interface they support:

| Client can... | Use |
|---|---|
| speak MCP (HTTP) | `POST https://<host>/mcp`, header `Authorization: Bearer <token>` |
| launch an MCP stdio server | `ZOTH_RELAY_TOKEN=<token> zoth-relay mcp-stdio` |
| import an OpenAPI spec | `https://<host>/openapi-actions.json` (bearer auth) |
| make HTTP calls | REST below |

Give every client its **own** agent and token, with the narrowest profile that works:
```bash
sudo zoth-relay agent add dots --profile read-only --dir ~/agent-workspace/shared
sudo zoth-relay agent add jev  --profile exec --dir ~/agent-workspace/jev --allow-cmd git --allow-cmd python3 --rate 30
```

REST (all also available without the `/v1` prefix):
```
GET  /v1/status
GET  /v1/dir?path=<dir>
GET  /v1/file?path=<file>                 -> {encoding: utf-8|base64, content}
POST /v1/file      {path, content, append?}
POST /v1/exec/run  {command, working_directory?, timeout?}  -> {stdout, stderr, exit_code, timed_out}
POST /v1/exec      same body -> NDJSON: {"stream":"stdout","data":..}... then {"exit_code":..}
```
```bash
curl -s -H "Authorization: Bearer $ZT" -H 'content-type: application/json' -d '{"command":"ls -la"}' $ZR/v1/exec/run
```
Status codes: 401 bad or revoked token · 403 outside your dirs, profile forbids it, or blocked command · 413 too large · 429 rate limit.

The child process gets `ZOTH_RELAY_AGENT=<name>` in its environment, and every audit line is tagged with the agent name.
