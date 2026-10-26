"""One persistent AGY conversation driven over its stream-json protocol.

    agy --input-format stream-json --output-format stream-json --sandbox -p=

stdin : one NDJSON message per turn  {"event":"user","message":{"content":[{"type":"text","text":...}]}}
stdout: {"event":"init"...}, {"event":"step_update","step_update":{"step_type":"agent_response",
         "state":"ACTIVE|DONE","text_delta":"..."}}, then {"event":"result","result":{"status":...}}
AGY runs turns sequentially and keeps context across them (verified with agy 1.4.2). There is no
mid-turn interrupt event, so barge-in mutes the rest of the current turn and queues the new input.
Every line is mirrored, human-readable, to `mirror_path` so a Konsole/tmux window can show it live.
"""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import time
from collections import deque
from typing import AsyncIterator, Callable


class AgyError(Exception):
    pass


class AgySession:
    def __init__(self, command: str = "agy", workdir: str = "~/.local/state/zoth-relay-mobile/agy-ws",
                 model: str = "", effort: str = "low", mirror_path: str = "", turn_timeout: float = 45.0,
                 argv_factory: Callable[[], list[str]] | None = None, first_token_timeout: float = 15.0,
                 resume: bool = True, label: str = ""):
        self.command, self.model, self.effort = command, model, effort
        self.workdir = os.path.expanduser(workdir)
        self.mirror_path = os.path.expanduser(mirror_path) if mirror_path else ""
        self.turn_timeout = turn_timeout
        self.argv_factory = argv_factory
        self.proc: asyncio.subprocess.Process | None = None
        self.conversation_id = ""
        self.turn_lock = asyncio.Lock()
        self.turns = 0
        self.chars_sent = 0
        self.started_at = 0.0
        self.first_token_timeout = first_token_timeout
        self.resume = resume          # False = never --conversation (rolled sessions start fresh)
        self.label = label
        self.stats: deque = deque(maxlen=200)   # (wall time, first-delta s, total s, chars sent)

    # ------------------------------------------------------------ process
    def argv(self) -> list[str]:
        if self.argv_factory:
            return self.argv_factory()
        exe = shutil.which(os.path.expanduser(self.command)) or shutil.which(os.path.expanduser("~/.local/bin/agy"))
        if not exe:
            raise AgyError("AGY CLI not found (expected ~/.local/bin/agy)")
        a = [exe, "--input-format", "stream-json", "--output-format", "stream-json", "--sandbox",
             "--disable-slash-commands", "--effort", self.effort]
        if self.model:
            a += ["--model", self.model]
        if self.conversation_id and self.resume:
            a += ["--conversation", self.conversation_id]   # resume context after a restart
        return a + ["-p="]

    @property
    def alive(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    async def start(self):
        if self.alive:
            return
        os.makedirs(self.workdir, mode=0o700, exist_ok=True)
        self.proc = await asyncio.create_subprocess_exec(
            *self.argv(), cwd=self.workdir, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL, limit=4 * 1024 * 1024)
        self.started_at = time.time()
        self.mirror(f"\n=== AGY session{(' ' + self.label) if self.label else ''} started {time.strftime('%H:%M:%S')} (stream-json) ===\n")

    async def stop(self):
        p, self.proc = self.proc, None
        if p and p.returncode is None:
            try:
                p.stdin.close()
                await asyncio.wait_for(p.wait(), 3)
            except (asyncio.TimeoutError, Exception):
                p.kill()
        self.mirror(f"\n=== AGY session stopped {time.strftime('%H:%M:%S')} ===\n")

    def mirror(self, text: str):
        if not self.mirror_path:
            return
        try:
            with open(self.mirror_path, "a", encoding="utf-8") as fh:
                fh.write(text)
        except OSError:
            pass

    # ------------------------------------------------------------ turns
    async def turn(self, text: str) -> AsyncIterator[str]:
        """Sends one user message; yields agent text deltas as they stream; returns at `result`."""
        async with self.turn_lock:
            for attempt in (1, 2):
                await self.start()
                try:
                    line = json.dumps({"event": "user", "message": {"content": [{"type": "text", "text": text}]}})
                    self.proc.stdin.write(line.encode() + b"\n")
                    await self.proc.stdin.drain()
                    break
                except (BrokenPipeError, ConnectionResetError, AttributeError):
                    await self.stop()
                    if attempt == 2:
                        raise AgyError("AGY process exited")
            self.mirror(f"\n── {time.strftime('%H:%M:%S')} → AGY {self.label}\n{text}\n── AGY ←\n")
            self.turns += 1
            self.chars_sent += len(text)
            t0 = time.monotonic()
            first = None
            deadline = t0 + self.turn_timeout
            while True:
                now = time.monotonic()
                left = deadline - now
                if first is None:
                    left = min(left, t0 + self.first_token_timeout - now)
                if left <= 0:
                    await self.stop()  # stuck turn: caller swaps to a fresh/pre-warmed session
                    self.stats.append((time.time(), None, round(time.monotonic() - t0, 2), len(text)))
                    raise AgyError("AGY turn timed out" if first is not None else "AGY slow to answer (no first token)")
                try:
                    raw = await asyncio.wait_for(self.proc.stdout.readline(), left)
                except asyncio.TimeoutError:
                    continue
                if not raw:
                    await self.stop()
                    raise AgyError("AGY process exited mid-turn")
                try:
                    ev = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                kind = ev.get("event")
                if kind == "init":
                    self.conversation_id = ev.get("conversation_id") or self.conversation_id
                elif kind == "step_update":
                    su = ev.get("step_update") or {}
                    self.conversation_id = su.get("conversation_id") or self.conversation_id
                    d = su.get("text_delta")
                    if su.get("step_type") == "agent_response" and d:
                        if first is None:
                            first = time.monotonic() - t0
                        self.mirror(d)
                        yield d
                    elif su.get("step_type") not in ("user_input", "agent_response") and su.get("state") == "ACTIVE":
                        self.mirror(f"\n  [{su.get('step_type')}]\n")
                elif kind == "result":
                    r = ev.get("result") or {}
                    total = time.monotonic() - t0
                    self.stats.append((time.time(), round(first, 2) if first is not None else None, round(total, 2), len(text)))
                    self.mirror(f"\n   ⏱ first {first if first is None else round(first, 1)}s · total {total:.1f}s · turn {self.turns}\n")
                    if r.get("status") not in (None, "SUCCESS"):
                        raise AgyError(f"AGY turn failed: {str(r.get('error'))[:200]}")
                    return
