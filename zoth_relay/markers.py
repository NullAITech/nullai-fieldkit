"""Streaming parser for AGY's speak markers: `[^ spoken line ^]`.

Text inside markers is spoken (emitted sentence by sentence as soon as each sentence is complete);
text outside is deeper context (kept for "tell me more", never spoken). Robust to markers split
across chunks, missing closers (flushed at end of turn), stray carets, and SILENT replies.
"""
from __future__ import annotations

import re

OPEN, CLOSE = "[^", "^]"
_SENT_END = re.compile(r"[.!?…]+[\"')\]]*(?=\s)")


class MarkerParser:
    def __init__(self, min_chunk_words: int = 0):
        self.inside = False
        self.buf = ""          # unconsumed raw text (may end with a partial marker)
        self.speech = ""       # text inside the current marker, not yet emitted
        self.context: list[str] = []
        self.spoken: list[str] = []
        self.min_chunk_words = min_chunk_words  # split the first long sentence at a comma for faster first audio
        self._first = True

    def feed(self, chunk: str) -> list[tuple[str, str]]:
        """-> list of ("speak", sentence) | ("context", text) | ("end_marker", "")."""
        self.buf += chunk
        out: list[tuple[str, str]] = []
        while self.buf:
            token = CLOSE if self.inside else OPEN
            i = self.buf.find(token)
            if i < 0:
                # keep a trailing partial token ("[" or "^") for the next chunk
                keep = 1 if self.buf.endswith(token[0]) else 0
                text, self.buf = self.buf[:len(self.buf) - keep], self.buf[len(self.buf) - keep:]
                self._take(text, out)
                break
            self._take(self.buf[:i], out)
            self.buf = self.buf[i + 2:]
            if self.inside:
                self._flush_speech(out, final=True)
                out.append(("end_marker", ""))
            self.inside = not self.inside
        return out

    def _take(self, text: str, out):
        if not text:
            return
        if self.inside:
            self.speech += text
            self._flush_speech(out, final=False)
        else:
            self.context.append(text)
            out.append(("context", text))

    def _flush_speech(self, out, final: bool):
        while True:
            m = _SENT_END.search(self.speech)
            cut = m.end() if m else -1
            if cut < 0 and self._first and self.min_chunk_words:
                c = self.speech.find(", ")
                if c > 0 and len(self.speech[:c].split()) >= self.min_chunk_words:
                    cut = c + 1
            if cut < 0:
                break
            self._emit(self.speech[:cut], out)
            self.speech = self.speech[cut:]
        if final:
            self._emit(self.speech, out)
            self.speech = ""

    def _emit(self, s: str, out):
        s = re.sub(r"\s+", " ", s).strip().strip("^").strip()
        if s and s.upper().rstrip(".!") != "SILENT":
            self._first = False
            self.spoken.append(s)
            out.append(("speak", s))

    def finish(self) -> list[tuple[str, str]]:
        """End of turn: a missing closer still speaks what was inside; a dangling '[' is context."""
        out: list[tuple[str, str]] = []
        rest, self.buf = self.buf, ""
        if self.inside:
            self.speech += rest
            self._flush_speech(out, final=True)
            out.append(("end_marker", ""))
            self.inside = False
        elif rest:
            self.context.append(rest)
            out.append(("context", rest))
        return out

    @property
    def context_text(self) -> str:
        t = re.sub(r"\s+", " ", "".join(self.context)).strip()
        return "" if t.upper().rstrip(".!") == "SILENT" else t
