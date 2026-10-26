#!/usr/bin/env bash
# Localhost smoke test: real uvicorn server, CLI-issued tokens, REST, NDJSON, MCP HTTP + stdio bridge.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:-.venv/bin/python}; PORT=${PORT:-18765}
T=$(mktemp -d); trap 'kill $SRV 2>/dev/null || true; rm -rf "$T"' EXIT
mkdir -p "$T/ws"
cat > "$T/config.yaml" <<C
port: $PORT
allowed_dirs: [$T/ws]
audit_log: $T/audit.log
agents_file: $T/agents.yaml
blocklist: ["/etc/shadow"]
C
export ZOTH_RELAY_CONFIG=$T/config.yaml
tok() { $PY -m zoth_relay agent add "$@" | tail -1 | tr -d ' '; }
MUSE=$(tok muse --profile exec --dir "$T/ws/muse")
GPT=$(tok chatgpt --profile workspace --dir "$T/ws/chatgpt")
DOTS=$(tok dots --profile read-only --dir "$T/ws/chatgpt")
$PY -m zoth_relay agent list
$PY -m zoth_relay serve > "$T/server.log" 2>&1 & SRV=$!
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null && break; sleep 0.2; done
U=http://127.0.0.1:$PORT
c() { curl -s -w ' [%{http_code}]\n' "$@"; }
echo "== no token";            c "$U/v1/status"
echo "== muse status";         curl -s -H "Authorization: Bearer $MUSE" "$U/v1/status" | $PY -c 'import json,sys;d=json.load(sys.stdin);print({k:d[k] for k in ("agent","profile","run_as","allowed_dirs")})'
echo "== legacy /status";      c -o /dev/null -H "Authorization: Bearer $MUSE" "$U/status"
echo "== chatgpt write";       c -H "Authorization: Bearer $GPT" -H 'content-type: application/json' -d '{"path":"hello.txt","content":"hi from gpt"}' "$U/v1/file"
echo "== dots read (shared)";  c -H "Authorization: Bearer $DOTS" "$U/v1/file?path=hello.txt"
echo "== dots write (denied)"; c -H "Authorization: Bearer $DOTS" -H 'content-type: application/json' -d '{"path":"x","content":"x"}' "$U/v1/file"
echo "== muse -> chatgpt dir"; c -H "Authorization: Bearer $MUSE" "$U/v1/file?path=$T/ws/chatgpt/hello.txt"
echo "== muse exec/run";       c -H "Authorization: Bearer $MUSE" -H 'content-type: application/json' -d '{"command":"echo $ZOTH_RELAY_AGENT; id -un"}' "$U/v1/exec/run"
echo "== muse NDJSON stream";  curl -sN -H "Authorization: Bearer $MUSE" -H 'content-type: application/json' -d '{"command":"for i in 1 2 3; do echo tick $i; sleep 0.2; done"}' "$U/v1/exec"
echo "== blocked";             c -H "Authorization: Bearer $MUSE" -H 'content-type: application/json' -d '{"command":"rm -rf /"}' "$U/v1/exec/run"
echo "== gpt exec (profile)";  c -H "Authorization: Bearer $GPT" -H 'content-type: application/json' -d '{"command":"id"}' "$U/v1/exec/run"
echo "== openapi-actions";     curl -s "$U/openapi-actions.json" | $PY -c 'import json,sys;from openapi_spec_validator import validate;d=json.load(sys.stdin);validate(d);print("valid", d["openapi"], sorted(o["operationId"] for p in d["paths"].values() for o in p.values()))'
echo "== dashboard (loopback)"; c -o /dev/null "$U/dashboard"
echo "== dashboard via tunnel hdr"; c -o /dev/null -H 'CF-Connecting-IP: 1.2.3.4' "$U/dashboard"
echo "== MCP http + stdio bridge"
ZR_URL=$U ZR_TOK=$MUSE $PY - <<'PYC'
import asyncio, os, sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamablehttp_client
U, TOK = os.environ["ZR_URL"], os.environ["ZR_TOK"]
async def use(s, label):
    await s.initialize()
    tools = sorted(t.name for t in (await s.list_tools()).tools)
    r = await s.call_tool("run_command", {"command": "echo via-$ZOTH_RELAY_AGENT"})
    st = await s.call_tool("status", {})
    print(f"  {label}: tools={tools} run_command={r.structuredContent['stdout'].strip()!r} agent={st.structuredContent['agent']}")
async def main():
    async with streamablehttp_client(U + "/mcp", headers={"Authorization": f"Bearer {TOK}"}) as (r, w, _):
        async with ClientSession(r, w) as s: await use(s, "http /mcp")
    p = StdioServerParameters(command=sys.executable, args=["-m", "zoth_relay", "mcp-stdio", "--url", U],
                              env={"ZOTH_RELAY_TOKEN": TOK, "PATH": os.environ["PATH"]}, cwd=os.getcwd())
    async with stdio_client(p) as (r, w):
        async with ClientSession(r, w) as s: await use(s, "stdio bridge")
asyncio.run(main())
PYC
echo "== MCP no token"; c -o /dev/null -X POST -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' -d '{}' "$U/mcp"
echo "== revoke dots (hot reload)"; $PY -m zoth_relay agent revoke dots; c -o /dev/null -H "Authorization: Bearer $DOTS" "$U/v1/status"
echo "== audit sample"; tail -n 4 "$T/audit.log" | cut -c1-160
echo "== agent tags in audit"; $PY -c "import json,collections;print(dict(collections.Counter(json.loads(l).get('agent','-') for l in open('$T/audit.log'))))"
if grep -qF "$MUSE" "$T/audit.log" "$T/agents.yaml" "$T/server.log"; then echo "FAIL: token leaked"; exit 1; else echo "OK: no plaintext token in audit/agents/server logs"; fi
echo SMOKE-OK
