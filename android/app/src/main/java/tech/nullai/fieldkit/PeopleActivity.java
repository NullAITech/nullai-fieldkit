package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

import tech.nullai.fieldkit.people.CharacterBoard;
import tech.nullai.fieldkit.people.PeopleVault;
import tech.nullai.fieldkit.voice.VoiceCapture;

/**
 * People & Character Boards: high-contrast everyday view of saved people and their 4-Angle
 * Character Boards, with an expandable Developer & Biometric Tools drawer at the bottom.
 */
public final class PeopleActivity extends Activity {
    private LinearLayout list;
    private PeopleVault vault;
    private boolean devExpanded = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vault = PeopleVault.get(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF090D14);
        sv.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));

        // 1. Header Row
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = text("Saved People", 22, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        TextView sub = text("4-Angle Character Boards & recognition cards stored privately on this phone.", 13, 0xFFCBD5E1);
        titleCol.addView(sub);
        topRow.addView(titleCol);

        Button closeBtn = pillBtn("← Back", 0xFFF8FAFC);
        closeBtn.setOnClickListener(v -> finish());
        topRow.addView(closeBtn);
        root.addView(topRow);

        // 2. How it works banner (digestible for everyday users)
        LinearLayout howCard = new LinearLayout(this);
        howCard.setOrientation(LinearLayout.VERTICAL);
        howCard.setBackgroundResource(R.drawable.bg_dark_card);
        howCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams howLp = new LinearLayout.LayoutParams(-1, -2);
        howLp.topMargin = dp(14);
        howCard.setLayoutParams(howLp);

        TextView howTitle = text("How Spotter Recognizes People", 14, 0xFF34D399);
        howTitle.setTypeface(Typeface.DEFAULT_BOLD);
        howCard.addView(howTitle);
        howCard.addView(text("Point your glasses or phone camera at someone and tap '◆ Save Person'. "
                + "Spotter captures 4 angles + their attire & posture. Whenever they walk into view again, "
                + "their Character Card pops up automatically on your screen.", 12.5f, 0xFFE2E8F0));
        root.addView(howCard);

        // 3. List of saved Character Boards
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        // 4. Collapsible Developer & Testing Drawer
        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCard.setBackgroundResource(R.drawable.bg_dark_subcard);
        devCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(-1, -2);
        devLp.topMargin = dp(22);
        devCard.setLayoutParams(devLp);

        TextView devToggle = text("▸ Developer & Biometric Vault Tools", 13, 0xFF94A3B8);
        devToggle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        devCard.addView(devToggle);

        LinearLayout devBody = new LinearLayout(this);
        devBody.setOrientation(LinearLayout.VERTICAL);
        devBody.setVisibility(View.GONE);
        devBody.setPadding(0, dp(10), 0, 0);

        TextView devNote = text("Vault: AES-256-GCM (AndroidKeyStore alias 'spotter_people_v1')\n"
                + "Face Descriptor: 124D  ·  Body Descriptor: 112D  ·  Voiceprint: 28D MFCC", 11.5f, 0xFFCBD5E1);
        devNote.setTypeface(Typeface.MONOSPACE);
        devBody.addView(devNote);

        Button sampleBtn = fullBtn("🧪 Load Sample 4-Angle Character Board (Demo)", 0xFF34D399, R.drawable.bg_accent_emerald);
        sampleBtn.setOnClickListener(v -> {
            CharacterBoard.createSamplePerson(this);
            render();
            Toast.makeText(this, "Generated sample 4-Angle Character Board!", Toast.LENGTH_SHORT).show();
        });
        devBody.addView(sampleBtn);

        Button all = fullBtn("Delete All Saved People & Voices", 0xFFFCA5A5, R.drawable.bg_dark_pill);
        all.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete all saved people?")
                .setMessage("Deletes every saved person, 4-angle character board, and voiceprint from this phone.")
                .setPositiveButton("Delete all", (d, w) -> { vault.deleteAll(); render(); })
                .setNegativeButton("Cancel", null).show());
        devBody.addView(all);

        devCard.addView(devBody);
        devToggle.setOnClickListener(v -> {
            devExpanded = !devExpanded;
            devToggle.setText((devExpanded ? "▾ " : "▸ ") + "Developer & Biometric Vault Tools");
            devBody.setVisibility(devExpanded ? View.VISIBLE : View.GONE);
        });
        root.addView(devCard);

        sv.addView(root);
        setContentView(sv);
        render();
    }

    private void render() {
        list.removeAllViews();
        if (vault.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setBackgroundResource(R.drawable.bg_dark_subcard);
            emptyBox.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(14);
            emptyBox.setLayoutParams(lp);

            TextView t1 = text("No Character Boards saved yet", 16, 0xFFFFFFFF);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            emptyBox.addView(t1);
            emptyBox.addView(text("Tap '◆ Save Person' on the Spotter home screen or during a live call with someone in view — or open Developer Tools below to load a sample Character Board.", 13, 0xFFCBD5E1));
            list.addView(emptyBox);
            return;
        }
        for (PeopleVault.Person p : vault.all()) list.addView(personBoardCard(p));
    }

    private View personBoardCard(PeopleVault.Person p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.bg_dark_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        // Header: Name + Role + Sighting Badge
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = text(p.name, 18f, 0xFFFFFFFF);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        nameCol.addView(tvName);

        TextView tvRole = text(p.role != null && !p.role.isEmpty() ? p.role : "Contact", 12.5f, 0xFF7DD3FC);
        tvRole.setTypeface(Typeface.DEFAULT_BOLD);
        nameCol.addView(tvRole);
        hdr.addView(nameCol);

        TextView tvSeen = text("SPOTTED " + Math.max(1, p.seenCount) + "x", 11f, 0xFF34D399);
        tvSeen.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hdr.addView(tvSeen);
        card.addView(hdr);

        // Composite 4-Angle Character Board image
        Bitmap boardBm = decode(!p.boardThumb.isEmpty() ? p.boardThumb : p.thumb);
        if (boardBm != null) {
            ImageView img = new ImageView(this);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(185));
            imgLp.topMargin = dp(10);
            imgLp.bottomMargin = dp(10);
            img.setLayoutParams(imgLp);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageBitmap(boardBm);
            img.setOnClickListener(v -> CharacterBoard.showFullDossierDialog(this, p, this::render));
            card.addView(img);
        }

        // Digestible Info Chart inside card
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        info.setBackgroundResource(R.drawable.bg_dark_subcard);

        info.addView(text("Last Seen: " + CharacterBoard.formatRelativeAndTime(this, p.lastSeenAt)
                + " · " + (p.lastSeenContext != null ? p.lastSeenContext : "Spotter HUD"), 12.5f, 0xFF34D399));
        info.addView(text("First Met: " + when(p.savedAt), 12.5f, 0xFFF8FAFC));
        if (p.attire != null && !p.attire.isEmpty()) {
            info.addView(text("Look & Attire: " + p.attire
                    + (p.buildSummary != null && !p.buildSummary.isEmpty() ? " · " + p.buildSummary : ""),
                    12.5f, 0xFF7DD3FC));
        }
        info.addView(text(p.voice.length > 0 ? "Voiceprint: Enrolled ✓ (" + when(p.voiceSavedAt) + ")" : "Voiceprint: Not recorded yet",
                12.5f, p.voice.length > 0 ? 0xFF34D399 : 0xFF94A3B8));
        if (p.notes != null && !p.notes.isEmpty()) {
            info.addView(text("Notes: " + p.notes, 12.5f, 0xFFFDE68A));
        }
        card.addView(info);

        // Collapsible Dev Biometric Telemetry
        card.addView(CharacterBoard.buildDevTelemetryToggle(
                this,
                p.face.length,
                p.body.length,
                p.lastMatchScore,
                p.lastMatchScore,
                p.lastMatchScore,
                p.upperColorRgb,
                p.lowerColorRgb,
                p.id
        ));

        // Spacious 3-button action row
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, dp(10), 0, 0);

        Button btnDossier = actionBtn("View / Edit Board", 0xFFF8FAFC);
        Button vo = actionBtn(p.voice.length > 0 ? "Forget Voice" : "Record Voice", 0xFF7DD3FC);
        Button del = actionBtn("Delete", 0xFFFCA5A5);

        btnDossier.setOnClickListener(v -> CharacterBoard.showFullDossierDialog(this, p, this::render));
        vo.setOnClickListener(v -> {
            if (p.voice.length > 0) { vault.setVoice(p.id, null); render(); return; }
            recordVoice(p);
        });
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete " + p.name + "'s Character Board?")
                .setPositiveButton("Delete", (d, w) -> { vault.delete(p.id); render(); })
                .setNegativeButton("Cancel", null).show());

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(40), 1.3f);
        lp1.rightMargin = dp(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(40), 1.1f);
        lp2.rightMargin = dp(6);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(40), 0.8f);

        acts.addView(btnDossier, lp1);
        acts.addView(vo, lp2);
        acts.addView(del, lp3);
        card.addView(acts);
        return card;
    }

    private void recordVoice(PeopleVault.Person p) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        Toast.makeText(this, "Listening — ask " + p.name + " to say a sentence.", Toast.LENGTH_LONG).show();
        VoiceCapture.capture(this, r -> runOnUiThread(() -> {
            if (r == null || !r.spoken() || r.print == null) {
                Toast.makeText(this, "Didn't catch a voice: " + (r == null ? "" : r.describe()),
                        Toast.LENGTH_LONG).show();
                return;
            }
            vault.setVoice(p.id, r.print);
            render();
        }));
    }

    static Bitmap decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception e) { return null; }
    }

    private String when(long ms) {
        if (ms <= 0) return "";
        Date d = new Date(ms);
        return DateFormat.getMediumDateFormat(this).format(d) + " " + DateFormat.getTimeFormat(this).format(d);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private Button fullBtn(String s, int color, int bgRes) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(12.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundResource(bgRes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private Button pillBtn(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(color);
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setBackgroundResource(R.drawable.bg_dark_pill);
        return b;
    }

    private Button actionBtn(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(11.5f);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackgroundResource(R.drawable.bg_dark_pill);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
