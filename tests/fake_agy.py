"""Stand-in for `agy --input-format stream-json --output-format stream-json`: same NDJSON protocol.
Reply text per turn comes from FAKE_AGY_SCRIPT (one reply per line, '|' splits stream chunks);
remembers all inputs and answers "RECALL" with the first input (context check)."""
import json, os, sys, time
replies = [l.rstrip("\n") for l in open(os.environ["FAKE_AGY_SCRIPT"])] if os.environ.get("FAKE_AGY_SCRIPT") else []
delay = float(os.environ.get("FAKE_AGY_DELAY", "0"))
cid = "conv-1234abcd"
print(json.dumps({"event": "init", "conversation_id": cid, "init": {"cwd": os.getcwd()}}), flush=True)
seen, n = [], 0
for line in sys.stdin:
    msg = json.loads(line)
    text = msg["message"]["content"][0]["text"]
    seen.append(text)
    with open(os.environ.get("FAKE_AGY_LOG", "/dev/null"), "a") as fh:
        fh.write(json.dumps(text) + "\n")
    reply = ("first was: " + seen[0][:20]) if text == "RECALL" else (replies[n] if n < len(replies) else "SILENT")
    n += 1
    print(json.dumps({"event": "step_update", "step_update": {"conversation_id": cid, "step_type": "user_input", "state": "DONE"}}), flush=True)
    for part in reply.split("|"):
        time.sleep(delay)
        print(json.dumps({"event": "step_update", "step_update": {"conversation_id": cid, "step_type": "agent_response", "state": "ACTIVE", "text_delta": part}}), flush=True)
    print(json.dumps({"event": "result", "result": {"conversation_id": cid, "status": "SUCCESS", "response": reply.replace("|", "")}}), flush=True)
