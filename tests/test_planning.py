"""FIELDKIT interview->plan + vision/memory-aware planning (tests first).

- Ambiguous goals get 2-3 clarifying questions (status=clarify) instead of
  a locked checklist; answers resubmitted with the goal produce the plan.
- Plans are conditioned on a live vision note + attached/prior memories.
- Assess calls carry step history (not amnesiac).
"""
import json

import httpx
import pytest

from zoth_relay.missions import MissionStore, assess_step, plan_interview
from zoth_relay.providers import OpenAICompatProvider


def _provider(script: dict):
    """Mock transport keyed by prompt markers from the interview/planning flow."""
    seen = []

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        content = body["messages"][-1]["content"]
        prompt = content[0]["text"] if isinstance(content, list) else content
        seen.append(prompt)
        for marker, text in script.items():
            if marker in prompt:
                sse = (f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\n"
                       + "data: [DONE]\n\n")
                return httpx.Response(200, text=sse,
                                      headers={"content-type": "text/event-stream"})
        raise AssertionError(f"unexpected prompt: {prompt[:120]}")

    p = OpenAICompatProvider("wonder", "https://up.example/v1", "m1",
                             api_key_env="DEFINITELY_MISSING_KEY")
    return p, httpx.MockTransport(handler), seen


def test_ambiguous_goal_returns_questions_first():
    p, t, _ = _provider({"Goal: fix the thing": json.dumps(
        {"questions": ["What machine?", "What is broken?", "What tools do you have?"]})})
    res = plan_interview_sync(p, "fix the thing", t)
    assert res.status == "clarify"
    assert len(res.questions) == 3
    assert res.steps == []


def test_answers_unlock_the_plan():
    p, t, _ = _provider({
        "Goal: fix the thing": json.dumps({"steps": ["Do A", "Do B"]}),
    })
    res = plan_interview_sync(p, "fix the thing", t,
                              answers=["The mower", "Filter clogged", "Wrench"])
    assert res.status == "ready"
    assert res.steps == ["Do A", "Do B"]


def test_memory_and_vision_condition_the_plan():
    p, t, seen = _provider({"Goal: change the filter": json.dumps({"steps": ["Pull it"]})})
    res = plan_interview_sync(
        p, "change the filter", t,
        hints=["Last time on step 3, you noted the left bolt was stripped."],
        memories=["engram: mower filter housing cracks easily"],
        vision_note="Eyes: a red mower with the filter cover off.")
    assert res.status == "ready"
    prompt = seen[0]
    assert "left bolt was stripped" in prompt
    assert "housing cracks easily" in prompt
    assert "filter cover off" in prompt


def test_assess_carries_step_history():
    store = MissionStore()
    m = store.create("Fix mower", ["Pull filter", "Fit filter"], provider="wonder")
    store.apply_event(m.id, "advance")
    p, t, seen = _provider({"Observation": json.dumps({"event": "none", "say": ""})})
    import asyncio
    asyncio.run(assess_step(p, store.get(m.id), "filter half out", t))
    assert "Pull filter (done)" in seen[0]
    assert "Fit filter (active)" in seen[0]


def plan_interview_sync(provider, goal, transport, **kw):
    import asyncio
    from zoth_relay.missions import plan_interview as pi
    return asyncio.run(pi(provider, goal, transport=transport, **kw))


# ------------------------------------------------------------ REST interview loop
import yaml as _yaml
from fastapi.testclient import TestClient as _TC

from zoth_relay.config import hash_token as _ht, load_config as _lc
from zoth_relay.providers import OpenAICompatProvider as _P
from zoth_relay.server import create_app as _app

_MOB = "zr_mobile_" + "m" * 40


def _sse(text: str):
    return httpx.Response(200, text=(f"data: {json.dumps({'choices': [{'delta': {'content': text}}]})}\n\n"
                                     + "data: [DONE]\n\n"),
                          headers={"content-type": "text/event-stream"})


def _two_stage_handler(req: httpx.Request):
    """Clarify first, then plan — the interview's normal upstream shape."""
    body = json.loads(req.content)
    content = body["messages"][-1]["content"]
    prompt = content[0]["text"] if isinstance(content, list) else content
    if "wearer answered" in prompt.lower():
        return _sse(json.dumps({"steps": ["Do A now", "Do B next"]}))
    return _sse(json.dumps({"questions": ["Which machine?", "What symptom?"]}))


def _rest_env(tmp_path, monkeypatch, handler=None):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(_yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": _ht(_MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(_yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "a.log"),
        "agents_file": "agents.yaml"}))

    guard = _app(_lc(str(tmp_path / "config.yaml")))
    prov = _P("wonder", "https://up.example/v1", "m", api_key_env="FAKE_KEY")
    guard.app.state.session_providers = {"wonder": prov, "spark": prov}
    guard.app.state.session_transport = httpx.MockTransport(handler or _two_stage_handler)
    return _TC(guard)


def test_rest_clarify_then_answers_plan(tmp_path, monkeypatch):
    c = _rest_env(tmp_path, monkeypatch)
    h = {"Authorization": f"Bearer {_MOB}"}
    r = c.post("/v1/missions", headers=h, json={"goal": "fix the thing", "provider": "wonder"})
    assert r.status_code == 200
    assert r.json()["status"] == "clarify"
    assert len(r.json()["questions"]) == 2
    r = c.post("/v1/missions", headers=h,
               json={"goal": "fix the thing", "provider": "wonder",
                     "answers": ["The mower", "Won't start"],
                     "memories": ["mower pull-cord replaced 2025"]})
    body = r.json()
    assert r.status_code == 201 and body["status"] == "ready"
    assert len(body["steps"]) == 2


def test_rest_planner_502_is_retried_before_the_client_sees_it(tmp_path, monkeypatch):
    """The judge taps Plan once; one upstream 502 must still yield a plan."""
    from zoth_relay import missions
    monkeypatch.setattr(missions, "RETRY_DELAY_S", 0)
    planner = []

    def flaky(req: httpx.Request):
        body = json.loads(req.content)
        content = body["messages"][-1]["content"]
        prompt = content[0]["text"] if isinstance(content, list) else content
        if not prompt.startswith("Goal:"):
            # Another test can leave a proactive session running, and Eyes/Advisor
            # then share this app's transport. That is not what this test measures.
            return _sse(json.dumps({"say": "standing by", "priority": 0}))
        planner.append(prompt[:40])
        if len(planner) == 1:
            return httpx.Response(502, text="bad gateway")
        return _two_stage_handler(req)

    c = _rest_env(tmp_path, monkeypatch, handler=flaky)
    r = c.post("/v1/missions", headers={"Authorization": f"Bearer {_MOB}"},
               json={"goal": "fix the thing", "provider": "wonder"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "clarify" and len(r.json()["questions"]) == 2
    assert len(planner) == 2, planner


# --------------------------------------------------- planner blips are retried
def _delta(text):
    return {"type": "delta", "text": text}


def _error(status=502):
    return {"type": "error", "status": status, "detail": "upstream error"}


class _Scripted:
    """Provider stand-in that plays one script of stream events per call.

    Lets a test express "the first attempt died, the second one worked" without
    inventing a fake upstream failure mode.
    """
    def __init__(self, *scripts, name="scripted"):
        self.name = name
        self.scripts = list(scripts)
        self.calls = 0

    async def stream_events(self, turn, rid, transport=None):
        self.calls += 1
        for ev in self.scripts[min(self.calls - 1, len(self.scripts) - 1)]:
            yield ev


def test_planner_stream_retries_once_when_upstream_fails_before_output(monkeypatch):
    """A single flaky 5xx must not hand the judge a dead Plan button."""
    from zoth_relay import missions
    monkeypatch.setattr(missions, "RETRY_DELAY_S", 0)
    p, t, calls = _provider_with_upstream_failure()
    res = plan_interview_sync(p, "fix the thing", t)
    assert res.status == "ready" and res.steps == ["Do A", "Do B"]
    assert len(calls) == 2


def _provider_with_upstream_failure():
    """First upstream call 502s, the second streams a valid checklist."""
    calls = []

    def handler(req: httpx.Request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(502, text="bad gateway")
        out = json.dumps({"steps": ["Do A", "Do B"]})
        sse = (f"data: {json.dumps({'choices': [{'delta': {'content': out}}]})}\n\n"
               + "data: [DONE]\n\n")
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})

    p = OpenAICompatProvider("wonder", "https://up.example/v1", "m1",
                             api_key_env="DEFINITELY_MISSING_KEY")
    return p, httpx.MockTransport(handler), calls


async def test_planner_is_not_retried_once_text_has_streamed(monkeypatch):
    """Half a checklist already streamed: retrying could duplicate it."""
    from zoth_relay import missions
    monkeypatch.setattr(missions, "RETRY_DELAY_S", 0)
    p = _Scripted([_delta('{"steps": ["Do A"]}'), _error()])
    with pytest.raises(missions.MissionError):
        await missions._complete(p, None, None)
    assert p.calls == 1


async def test_planner_raises_after_the_single_retry(monkeypatch):
    """Two failures in a row still surface as an error — no silent hang."""
    from zoth_relay import missions
    monkeypatch.setattr(missions, "RETRY_DELAY_S", 0)
    p = _Scripted([_error()])
    with pytest.raises(missions.MissionError):
        await missions._complete(p, None, None)
    assert p.calls == 2
