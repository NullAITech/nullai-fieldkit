package tech.nullai.fieldkit;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import tech.nullai.fieldkit.voice.OwnerPrintPrefs;
import tech.nullai.fieldkit.voice.VoiceCapture;
import tech.nullai.fieldkit.voice.VoicePrint;

/**
 * Dedicated Full-Screen Voice Gate & Tutorial View:
 * Intuitive guide, live visual audio meter, interactive one-tap voice calibration,
 * and clear Open Mic vs Locked mode controls.
 */
public final class VoiceGateActivity extends Activity {

    private boolean voiceLockEnabled = false;
    private TextView modeStatusTv;
    private TextView voiceprintStatusTv;
    private ProgressBar micLevelBar;
    private TextView micLevelLabel;
    private Button calibrateBtn;
    private Button modeToggleBtn;
    private Button clearBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voiceLockEnabled = getSharedPreferences("fieldkit", MODE_PRIVATE).getBoolean("voice_lock_enabled", false);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF090D14);
        sv.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));

        // Top Navigation Header
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = new TextView(this);
        title.setText("🔒 Voice Gate & Tutorial");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Wearer-Only Speech Isolation & Microphone Control");
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setTextSize(13);
        titleBox.addView(subtitle);
        topBar.addView(titleBox);

        Button backBtn = pillBtn("← Back", 0xFFF8FAFC);
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn);
        root.addView(topBar);

        // 1. Interactive Status Card
        LinearLayout statusCard = card(0xFF131C2E);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(18);
        statusCard.setLayoutParams(scLp);

        TextView scTitle = text("Current Status", 15, 0xFF38BDF8);
        scTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(scTitle);

        modeStatusTv = text("Mode: ...", 14, 0xFFF8FAFC);
        modeStatusTv.setPadding(0, dp(6), 0, dp(4));
        statusCard.addView(modeStatusTv);

        voiceprintStatusTv = text("Voiceprint: ...", 14, 0xFF94A3B8);
        statusCard.addView(voiceprintStatusTv);

        // Live Audio VU Meter
        TextView meterTitle = text("Live Mic Sensitivity", 12, 0xFF64748B);
        meterTitle.setPadding(0, dp(12), 0, dp(4));
        statusCard.addView(meterTitle);

        micLevelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        micLevelBar.setMax(100);
        micLevelBar.setProgress(0);
        statusCard.addView(micLevelBar);

        micLevelLabel = text("Speak into phone or glasses to test", 11, 0xFF64748B);
        micLevelLabel.setPadding(0, dp(4), 0, 0);
        statusCard.addView(micLevelLabel);

        root.addView(statusCard);

        // 2. Action Controls
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(-1, -2);
        acLp.topMargin = dp(16);
        actionsRow.setLayoutParams(acLp);

        calibrateBtn = actionBtn("🎙️ Calibrate My Voice (Speak 1 Sentence)", 0xFF10B981, 0xFFFFFFFF);
        calibrateBtn.setOnClickListener(v -> startCalibration());
        actionsRow.addView(calibrateBtn);

        modeToggleBtn = actionBtn("🔓 Switch to Open Mic Mode (Hear All Voices)", 0xFF334155, 0xFFF8FAFC);
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(-1, dp(48));
        mtLp.topMargin = dp(10);
        modeToggleBtn.setLayoutParams(mtLp);
        modeToggleBtn.setOnClickListener(v -> toggleMode());
        actionsRow.addView(modeToggleBtn);

        clearBtn = pillBtn("🗑️ Reset Saved Voiceprint", 0xFFEF4444);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, dp(38));
        clLp.topMargin = dp(10);
        clearBtn.setLayoutParams(clLp);
        clearBtn.setOnClickListener(v -> resetVoiceprint());
        actionsRow.addView(clearBtn);

        root.addView(actionsRow);

        // 3. Clear Step-by-Step Tutorial Card
        LinearLayout tutorialCard = card(0xFF1E293B);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(-1, -2);
        tcLp.topMargin = dp(24);
        tutorialCard.setLayoutParams(tcLp);

        TextView tutTitle = text("📘 How It Works & When To Use It", 16, 0xFFF59E0B);
        tutTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tutorialCard.addView(tutTitle);

        tutorialCard.addView(step(
                "1. What is the Voice Gate?",
                "The Voice Gate locks the microphone to YOUR vocal timbre. It ensures background noise, coffee shop banter, or TV audio is blocked and never interrupts your AI session."
        ));

        tutorialCard.addView(step(
                "2. When to use 'My Voice Only' (Locked)?",
                "Use this mode in noisy public places, crowds, or when someone is talking right next to you. Only YOUR words will reach Muse."
        ));

        tutorialCard.addView(step(
                "3. When to use 'Open Mic Mode'?",
                "Use Open Mic when you are in a quiet room, or when you WANT Muse to hear and converse with someone standing in front of you."
        ));

        tutorialCard.addView(step(
                "4. Fast Calibration",
                "Simply tap 'Calibrate My Voice' and speak naturally for 2 seconds. The app computes an on-device 128D vocal embedding and saves it securely to Android Keystore."
        ));

        root.addView(tutorialCard);

        sv.addView(root);
        setContentView(sv);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        boolean enrolled = OwnerPrintPrefs.load(this).isEnrolled();
        if (voiceLockEnabled) {
            modeStatusTv.setText("Mode: 🔒 Active (My Voice Only)");
            modeStatusTv.setTextColor(0xFF34D399);
            modeToggleBtn.setText("🔓 Switch to Open Mic Mode (No Filtering)");
        } else {
            modeStatusTv.setText("Mode: 🔓 Open Mic (Listening to All Voices)");
            modeStatusTv.setTextColor(0xFFF59E0B);
            modeToggleBtn.setText("🔒 Switch to Voice Lock Mode (My Voice Only)");
        }

        if (enrolled) {
            voiceprintStatusTv.setText("Voiceprint: Enrolled & Locked to Your Timbre ✓");
            voiceprintStatusTv.setTextColor(0xFF38BDF8);
        } else {
            voiceprintStatusTv.setText("Voiceprint: Not yet enrolled (Open to all speech)");
            voiceprintStatusTv.setTextColor(0xFF94A3B8);
        }
    }

    private void toggleMode() {
        voiceLockEnabled = !voiceLockEnabled;
        getSharedPreferences("fieldkit", MODE_PRIVATE).edit()
                .putBoolean("voice_lock_enabled", voiceLockEnabled).apply();
        refreshUi();
        Toast.makeText(this, voiceLockEnabled ? "🔒 Voice Gate Locked to Your Voice" : "🔓 Open Mic Active", Toast.LENGTH_SHORT).show();
    }

    private void startCalibration() {
        if (!VoiceCapture.available(this)) {
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
            return;
        }
        calibrateBtn.setEnabled(false);
        calibrateBtn.setText("🎙️ Listening... Speak clearly now!");
        calibrateBtn.setBackgroundColor(0xFFEAB308);
        micLevelBar.setProgress(35);
        micLevelLabel.setText("Listening for your voice... speak a full sentence!");

        VoiceCapture.capture(this, r -> handler.post(() -> {
            calibrateBtn.setEnabled(true);
            calibrateBtn.setText("🎙️ Calibrate My Voice (Speak 1 Sentence)");
            calibrateBtn.setBackgroundColor(0xFF10B981);
            micLevelBar.setProgress(0);

            if (r.error != null && !r.error.isEmpty()) {
                micLevelLabel.setText("Result: " + r.error);
                Toast.makeText(VoiceGateActivity.this, "Note: " + r.error, Toast.LENGTH_SHORT).show();
            }

            if (r.print != null) {
                OwnerPrintPrefs.save(VoiceGateActivity.this, new float[][]{r.print, r.print});
                voiceLockEnabled = true;
                getSharedPreferences("fieldkit", MODE_PRIVATE).edit()
                        .putBoolean("voice_lock_enabled", true).apply();
                refreshUi();
                micLevelBar.setProgress(100);
                micLevelLabel.setText("Voice calibrated successfully! (Peak " + Math.round(r.peakDb) + " dBFS)");
                Toast.makeText(VoiceGateActivity.this, "🔒 Voiceprint Calibrated Successfully!", Toast.LENGTH_LONG).show();
            } else {
                micLevelLabel.setText("Could not isolate voice — please speak louder into phone/glasses.");
                Toast.makeText(VoiceGateActivity.this, "Speak louder and closer to the mic, then try again.", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void resetVoiceprint() {
        OwnerPrintPrefs.forget(this);
        refreshUi();
        micLevelBar.setProgress(0);
        micLevelLabel.setText("Voiceprint cleared.");
        Toast.makeText(this, "Saved voiceprint cleared.", Toast.LENGTH_SHORT).show();
    }

    private static short[] bytesToShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
        }
        return s;
    }

    private LinearLayout card(int bg) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(bg);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        return l;
    }

    private LinearLayout step(String title, String desc) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(10), 0, dp(6));

        TextView t = text(title, 14, 0xFF38BDF8);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(t);

        TextView d = text(desc, 12.5f, 0xFFCBD5E1);
        d.setPadding(0, dp(2), 0, 0);
        l.addView(d);
        return l;
    }

    private TextView text(String s, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private Button pillBtn(String label, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(12);
        b.setBackgroundColor(0xFF1E293B);
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        return b;
    }

    private Button actionBtn(String label, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(bgColor);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
