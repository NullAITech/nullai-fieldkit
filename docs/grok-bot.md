# Connecting Grok Bot

Grok Bot works from its own Linux box, so it reaches zoth-relay over the public tunnel URL using plain REST (curl) or MCP.

1. On the ZOTHOS/Parrot machine:
   ```bash
   sudo zoth-relay agent add grok-bot --profile exec      # or workspace / read-only
   cloudflared tunnel --url http://localhost:8765          # copy the https://*.trycloudflare.com URL
   ```
2. Give Grok Bot the URL and token. Store the token in Grok Bot's private store, not in a shared chat or repo.
3. One-liners Grok Bot can run:
   ```bash
   export ZR=https://<tunnel>.trycloudflare.com ZT=<token>
   curl -s -H "Authorization: Bearer $ZT" $ZR/v1/status
   curl -s -H "Authorization: Bearer $ZT" -H 'content-type: application/json' \
        -d '{"command":"uname -a"}' $ZR/v1/exec/run
   curl -sN -H "Authorization: Bearer $ZT" -H 'content-type: application/json' \
        -d '{"command":"make test"}' $ZR/v1/exec          # NDJSON live stream
   ```
4. If your Grok Bot workspace lets you add a custom MCP server, point it at `$ZR/mcp` (streamable HTTP) with the header `Authorization: Bearer <token>`.

Revoke the token at any time with `sudo zoth-relay agent revoke grok-bot`. The revoke applies on the next request, with no restart needed.
