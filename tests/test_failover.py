"""Live model failover (Nov 2): Meta primary errors/stalls -> Spark Open on Baseten."""
import asyncio

from zoth_relay.providers import FailoverProvider, Provider, Turn, with_failover
from zoth_relay.sessions import Session


class Fake(Provider):
    def __init__(self, name, tokens=("ok.",), fail_after=None, delay=0.0, key_env=""):
        super().__init__(name, "http://x", name + "-model", api_key_env=key_env)
        self.tokens, self.fail_after, self.delay, self.seen = tokens, fail_after, delay, []

    async def stream_events(self, turn, request_id, transport=None):
        self.seen.append(turn)
        yield {"type": "start", "id": request_id, "agent": self.name, "model": self.model}
        for i, t in enumerate(self.tokens):
            if self.fail_after is not None and i >= self.fail_after:
                yield {"type": "error", "status": 502, "detail": "boom"}
                return
            await asyncio.sleep(self.delay)
            yield {"type": "delta", "text": t}
        yield {"type": "done", "id": request_id, "text": "".join(self.tokens), "ms": 1, "first_ms": 1}


async def collect(p, turn=None):
    return [ev async for ev in p.stream_events(turn or Turn(prompt="hi"), "r1")]


def test_primary_ok_no_switch():
    p = FailoverProvider(Fake("spark"), Fake("spark-open"), timeout=1)
    evs = asyncio.run(collect(p))
    assert not [e for e in evs if e["type"] == "model_switched"]
    assert evs[-1]["type"] == "done" and evs[0]["agent"] == "spark"


def test_error_mid_stream_switches_and_keeps_turn():
    prim, fb = Fake("spark", ("a", "b"), fail_after=1), Fake("spark-open", ("Go left.",))
    turn = Turn(prompt="what now", history=[{"role": "user", "content": "earlier"}])
    evs = asyncio.run(collect(FailoverProvider(prim, fb, timeout=1), turn))
    sw = [e for e in evs if e["type"] == "model_switched"][0]
    assert sw["from"] == "spark" and sw["to"] == "spark-open" and sw["reason"] == "error"
    assert sw["to_model"] == "spark-open-model"
    assert fb.seen[0] is turn  # same prompt + history: context kept
    assert evs[-1]["type"] == "done" and evs[-1]["text"] == "Go left."
    assert evs[-1]["agent"] == "spark-open" and evs[-1]["switched_from"] == "spark"
    assert not [e for e in evs if e["type"] == "error"]


def test_timeout_switches():
    prim, fb = Fake("spark", ("slow",), delay=0.5), Fake("spark-open", ("fast",))
    evs = asyncio.run(collect(FailoverProvider(prim, fb, timeout=0.05)))
    sw = [e for e in evs if e["type"] == "model_switched"][0]
    assert sw["reason"] == "timeout"
    assert evs[-1]["text"] == "fast"


def test_both_fail_reports_error():
    evs = asyncio.run(collect(FailoverProvider(Fake("spark", fail_after=0),
                                               Fake("spark-open", fail_after=0), timeout=1)))
    assert evs[-1]["type"] == "error"


def test_with_failover_noop_without_baseten(monkeypatch):
    monkeypatch.delenv("BASETEN_API_KEY", raising=False)
    prim = Fake("spark")
    ps = {"spark": prim, "spark-open": Fake("spark-open", key_env="BASETEN_API_KEY")}
    assert with_failover(ps)["spark"] is prim


def test_with_failover_wraps_meta_presets(monkeypatch):
    monkeypatch.setenv("BASETEN_API_KEY", "x")
    monkeypatch.setenv("ZOTH_FAILOVER_TIMEOUT_S", "3.5")
    prim = Fake("spark")
    ps = with_failover({"spark": prim, "local": Fake("local"),
                        "spark-open": Fake("spark-open", key_env="BASETEN_API_KEY")})
    assert isinstance(ps["spark"], FailoverProvider) and ps["spark"].timeout == 3.5
    assert ps["spark"].name == "spark" and ps["spark"].public()["failover"] == "spark-open"
    assert not isinstance(ps["local"], FailoverProvider)
    assert not isinstance(ps["spark-open"], FailoverProvider)
    monkeypatch.setenv("ZOTH_FAILOVER", "0")
    assert with_failover({"spark": prim, "spark-open": ps["spark-open"]})["spark"] is prim


def test_session_forwards_switch_and_keeps_only_fallback_text():
    out = []
    async def send(m):
        out.append(m)
    s = Session(FailoverProvider(Fake("spark", ("Par", "tial"), fail_after=1),
                                 Fake("spark-open", ("Fixed.",)), timeout=1), send)
    asyncio.run(s.run_turn("help"))
    assert any(m["type"] == "model_switched" for m in out)
    assert s.ctx.history()[-1] == {"role": "assistant", "content": "Fixed."}
    assert s.replay_log[-1]["model"] == "spark-open"
