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


def _rest_env(tmp_path, monkeypatch):
    monkeypatch.setenv("FAKE_KEY", "test-key-123")
    (tmp_path / "ws").mkdir()
    (tmp_path / "agents.yaml").write_text(_yaml.safe_dump({"agents": {
        "pixel": {"token_sha256": _ht(_MOB), "profile": "mobile",
                  "allowed_dirs": [str(tmp_path / "ws")]}}}))
    (tmp_path / "config.yaml").write_text(_yaml.safe_dump({
        "allowed_dirs": [str(tmp_path / "ws")], "audit_log": str(tmp_path / "a.log"),
        "agents_file": "agents.yaml"}))

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        content = body["messages"][-1]["content"]
        prompt = content[0]["text"] if isinstance(content, list) else content
        if "wearer answered" in prompt.lower():
            out = json.dumps({"steps": ["Do A now", "Do B next"]})
        else:
            out = json.dumps({"questions": ["Which machine?", "What symptom?"]})
        sse = (f"data: {json.dumps({'choices': [{'delta': {'content': out}}]})}\n\n"
               + "data: [DONE]\n\n")
        return httpx.Response(200, text=sse, headers={"content-type": "text/event-stream"})

    guard = _app(_lc(str(tmp_path / "config.yaml")))
    prov = _P("wonder", "https://up.example/v1", "m", api_key_env="FAKE_KEY")
    guard.app.state.session_providers = {"wonder": prov, "spark": prov}
    guard.app.state.session_transport = httpx.MockTransport(handler)
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
