package tech.nullai.fieldkit.avatar;

import android.content.Context;
import android.util.AttributeSet;

/**
 * 2D Spotter Presence: dithered/braille/ASCII/lattice face with blinking,
 * gaze and a level-driven mouth (see PresenceWebView). Replaces the earlier
 * Canvas pearl character; keeps its API so CallActivity is unchanged.
 */
public class Muse2DView extends PresenceWebView {
    private String status = "";
    public Muse2DView(Context c) { this(c, null); }
    public Muse2DView(Context c, AttributeSet a) { super(c, a, "2d"); }

    /** Kept for API compatibility; the face looks the same with eyes on. */
    public void setVideoMode(boolean v) { }

    public void setStatus(String s) { if (s != null) status = s; }
    public String status() { return status; }
}
