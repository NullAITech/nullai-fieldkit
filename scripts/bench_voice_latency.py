"""Relay-side voice latency bench with a simulated token stream (no keys, no network).

Measures when the client can START speaking:
  before: only on the final "done" event (full reply text)
  after:  on the first streamed "speak" chunk
Token timing is simulated (first token TTFT_MS, then TOKEN_MS per token), so the
numbers show the pipeline's effect, not a real model's speed.
"""
import asyncio, os, statistics, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from zoth_relay.providers import Provider
from zoth_relay.sessions import Session

TTFT_MS, TOKEN_MS = 350, 35
REPLY = ("Stop for a second, the housing has a crack near the left clamp. "
         "Loosen the clamp gently, lift the old filter straight up, and keep the screws in the cover.")
TOKENS = [w + " " for w in REPLY.split()]


class Sim(Provider):
    def __init__(self):
        super().__init__("sim", "http://x", "m")
    async def stream_events(self, turn, rid, transport=None):
        async def pieces():
            await asyncio.sleep(TTFT_MS / 1000)
            for i, t in enumerate(TOKENS):
                if i:
                    await asyncio.sleep(TOKEN_MS / 1000)
                yield t
        async for ev in self._emit(rid, pieces()):
            yield ev


async def once():
    t0 = time.monotonic(); marks = {}
    async def send(m):
        k = m.get("type")
        if k in ("speak", "done") and k not in marks:
            marks[k] = (time.monotonic() - t0) * 1000
    await Session(Sim(), send).run_turn("what now")
    return marks["done"], marks["speak"]


async def main():
    rs = [await once() for _ in range(5)]
    d = statistics.median(r[0] for r in rs); s = statistics.median(r[1] for r in rs)
    print(f"tokens={len(TOKENS)} ttft={TTFT_MS}ms token={TOKEN_MS}ms runs=5")
    print(f"before (speak at done):      {d:.0f} ms")
    print(f"after  (first speak chunk):  {s:.0f} ms")

asyncio.run(main())
