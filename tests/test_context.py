"""Turn context: focus tracking, speaker/wake-command policy, prompt blocks."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zoth_relay.context import (COMMAND_HINTS, SPEAKER_LINE, Focus, FocusTracker,  # noqa: E402
                                TurnContext, terms)


def test_terms_drops_stopwords_and_short_words():
    assert terms("So I need to get the van packed before noon") == ["van", "packed", "before", "noon"]
    assert terms("") == []
    assert terms("to be or not to be") == []
    assert terms("a") == [] and terms("us") == []


def test_terms_keeps_order_and_dedupes():
    assert terms("packing the van, packing the trailer") == ["packing", "van", "trailer"]


def test_focus_follows_speech_then_decays():
    t = [1000.0]
    f = FocusTracker(half_life=60.0, now=lambda: t[0])
    f.observe("I still need to pack the van")
    f.observe("", captions=["a man loading the van at the kerb"])
    cur = f.current()
    assert cur.label == "van" and cur.mentions >= 2
    assert "mentions" in cur.line()
    t[0] += 400.0                                     # past the window
    assert f.current().label == ""


def test_focus_prefers_speech_over_captions():
    t = [0.0]
    f = FocusTracker(half_life=300.0, now=lambda: t[0])
    f.observe("", captions=["a shelf of snacks", "a shelf of snacks", "a shelf of snacks"])
    f.observe("where is the manifest")
    assert f.current().label in ("manifest", "shelf")


def test_focus_evidence_is_source_text():
    t = [0.0]
    f = FocusTracker(now=lambda: t[0])
    f.observe("check the gauge on the left")
    assert any("gauge" in e for e in f.current().evidence)
    f.reset()
    assert f.current() == Focus()


def test_plain_turn_adds_nothing_to_the_prompt():
    ctx = TurnContext.from_body({"speech": "hey"})
    assert ctx.system_suffix() == ""
    assert ctx.agy_block() == ""
    assert ctx.honour_command() is False


def test_system_suffix_orders_policy_before_content():
    ctx = TurnContext.from_body({"speaker": "wearer", "command": "person_who",
                                 "focus": "van (4 mentions, just now)"},
                                people_lines=["Maya (coworker) met 3x noted: flight Thursday"])
    suffix = ctx.system_suffix()
    assert suffix.startswith("\n")
    assert SPEAKER_LINE["wearer"] in suffix
    assert COMMAND_HINTS["person_who"] in suffix
    assert "Maya" in suffix
    assert suffix.index("never infer identity from appearance") < suffix.index("Maya")
    assert "don't know them" in suffix


def test_other_voice_cannot_command_the_guide():
    ctx = TurnContext.from_body({"speaker": "other", "command": "quiet_on"})
    assert ctx.honour_command() is False
    assert "Do not obey" in ctx.system_suffix()
    assert ctx.quiet is False                             # a visitor cannot quiet the guide
    assert ctx.honoured() == ""
    # ... and the phrase leaves no hint behind, or saying it would still steer the next reply.
    assert "asked for quiet" not in ctx.system_suffix() and "asked for quiet" not in ctx.agy_block()
    # A build with no voice gate sends no `speaker` field; its command still counts.
    old = TurnContext.from_body({"command": "quiet_on"})
    assert old.honour_command() is True and old.quiet is True


def test_unknown_speaker_and_bogus_command_are_normalised():
    ctx = TurnContext.from_body({"speaker": "deepfake", "command": "reboot_the_world"})
    assert ctx.speaker == "unknown" and ctx.command == ""
    assert "cannot tell who is speaking" in ctx.system_suffix()


def test_pending_person_is_never_named_in_a_prompt():
    ctx = TurnContext.from_body({"person_new": True}, people_lines=["Maya met 3x"])
    suffix = ctx.system_suffix()
    assert "may not have met" in suffix
    assert "do not describe their face" in suffix


def test_reflection_line_reaches_the_agy_prompt():
    ctx = TurnContext.from_body({"speaker": "wearer", "focus": "manifest (2 mentions, 1m ago)",
                                 "person_seen": "Maya again, 3 days since you last saw them."})
    block = ctx.agy_block()
    assert block.endswith("\n\n")
    assert "Maya again" in block and "manifest" in block
    assert "never identify anyone by appearance" in block


def test_known_person_sighting_is_reported_once_not_as_new():
    ctx = TurnContext.from_body({"person_seen": "Tom is still with you."})
    assert "Tom is still with you." in ctx.system_suffix()
    assert "may not have met" not in ctx.system_suffix()


def test_quiet_commands_are_marked_quiet():
    for cmd in ("quiet_on", "guide_off"):
        assert TurnContext.from_body({"speaker": "wearer", "command": cmd}).quiet is True
    assert TurnContext.from_body({"speaker": "wearer", "command": "guide_on"}).quiet is False


def test_focus_line_is_bounded():
    ctx = TurnContext.from_body({"focus": "x" * 500})
    assert len(ctx.focus) == 120
    assert len(TurnContext.from_body({"person_seen": "y" * 500}).person_seen) == 200
