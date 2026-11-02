package tech.nullai.fieldkit.avatar;

import android.content.Context;
import android.util.AttributeSet;

/** 3D Spotter Presence: rotating Fibonacci point-cloud head (see PresenceWebView). */
public class Muse3DView extends PresenceWebView {
    public Muse3DView(Context c) { this(c, null); }
    public Muse3DView(Context c, AttributeSet a) { super(c, a, "3d"); }
}
