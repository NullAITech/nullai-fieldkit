"""Tests for Spotter RTMP Streamer & Ingest Bridge."""
import subprocess
import pytest
from zoth_relay.rtmp_streamer import RTMPStreamer, PRESETS


def test_rtmp_presets_structure():
    assert "x" in PRESETS
    assert "twitch" in PRESETS
    assert "youtube" in PRESETS
    assert "obs" in PRESETS
    assert "pscp.tv" in PRESETS["x"]["url_template"]
    assert "live.twitch.tv" in PRESETS["twitch"]["url_template"]


def test_build_destination_url():
    s = RTMPStreamer()
    # Presets
    x_url = s.build_destination_url("x", "my_secret_key_123")
    assert x_url == "rtmps://prod-fastly-us-east-1.video.pscp.tv:443/x/my_secret_key_123"

    twitch_url = s.build_destination_url("twitch", "live_key_999")
    assert twitch_url == "rtmp://live.twitch.tv/app/live_key_999"

    obs_url = s.build_destination_url("obs", "spotter")
    assert obs_url == "rtmp://127.0.0.1:1935/live/spotter"

    # Custom URL
    custom = s.build_destination_url("custom", "", custom_url="rtmp://custom.server.com/live/stream1")
    assert custom == "rtmp://custom.server.com/live/stream1"


def test_mask_url():
    s = RTMPStreamer()
    masked = s._mask_url("rtmp://live.twitch.tv/app/live_123456789_abcdef")
    assert "live_123456789_abcdef" not in masked
    assert "•" in masked
    assert masked.startswith("rtmp://live.twitch.tv/app/liv")


def test_push_and_get_frame():
    s = RTMPStreamer()
    assert s.get_latest_frame() is None
    dummy = b"\xff\xd8\xff\xe0\x00\x10JFIFdummyframe"
    s.push_frame(dummy)
    assert s.get_latest_frame() == dummy


def test_status_when_idle():
    s = RTMPStreamer()
    st = s.status()
    assert st["active"] is False
    assert st["frames_sent"] == 0
    assert "presets_available" in st
