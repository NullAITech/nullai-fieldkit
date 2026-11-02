package tech.nullai.fieldkit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

import tech.nullai.fieldkit.people.PeopleVault;
import tech.nullai.fieldkit.voice.VoiceCapture;

/**
 * People & Voices (Nov 2, hackathon): everyone the wearer chose to remember, with the frame
 * from the moment they were saved, the date, and whether a voice is saved. Rename, record or
 * delete a voice, delete a person, delete everything. All local, encrypted (see PeopleVault).
 */
public final class PeopleActivity extends Activity {
    private LinearLayout list;
    private PeopleVault vault;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vault = PeopleVault.get(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B0C10);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView title = text("People & Voices", 20, 0xFFE8EEF7);
        root.addView(title);
        root.addView(text("Saved only on this phone, encrypted, and only when you chose to. "
                + "Spotter never names anyone you did not name.", 12, 0xFF9FB3C8));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        Button all = button("Delete everyone", 0xFFFF9B9B);
        all.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete every saved person and voice from this phone?")
                .setPositiveButton("Delete all", (d, w) -> { vault.deleteAll(); render(); })
                .setNegativeButton("Cancel", null).show());
        root.addView(all);
        sv.addView(root);
        setContentView(sv);
        render();
    }

    private void render() {
        list.removeAllViews();
        if (vault.isEmpty()) {
            list.addView(text("\nNobody saved yet. During a call, tap Remember with someone in view.\n",
                    14, 0xFFC9D6E8));
            return;
        }
        for (PeopleVault.Person p : vault.all()) list.addView(row(p));
    }

    private View row(PeopleVault.Person p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackgroundResource(R.drawable.bg_pill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = decode(p.thumb);
        if (bm != null) img.setImageBitmap(bm); else img.setBackgroundColor(0xFF22262E);
        card.addView(img);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(text(p.name, 16, 0xFFE8EEF7));
        col.addView(text("Saved " + when(p.savedAt), 11, 0xFF9FB3C8));
        col.addView(text(p.voice.length > 0 ? "Voice saved " + when(p.voiceSavedAt) : "No voice saved",
                11, p.voice.length > 0 ? 0xFF8FE3B0 : 0xFF9FB3C8));
        LinearLayout acts = new LinearLayout(this);
        Button rn = small("Rename"), vo = small(p.voice.length > 0 ? "Forget voice" : "Save voice"),
                del = small("Delete");
        rn.setOnClickListener(v -> {
            EditText e = new EditText(this);
            e.setText(p.name);
            new AlertDialog.Builder(this).setTitle("Rename").setView(e)
                    .setPositiveButton("Save", (d, w) -> { vault.rename(p.id, e.getText().toString()); render(); })
                    .setNegativeButton("Cancel", null).show();
        });
        vo.setOnClickListener(v -> {
            if (p.voice.length > 0) { vault.setVoice(p.id, null); render(); return; }
            recordVoice(p);
        });
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete " + p.name + "?")
                .setPositiveButton("Delete", (d, w) -> { vault.delete(p.id); render(); })
                .setNegativeButton("Cancel", null).show());
        acts.addView(rn); acts.addView(vo); acts.addView(del);
        col.addView(acts);
        card.addView(col);
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

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(color); b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private Button small(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(11); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(36)); b.setMinimumHeight(dp(36)); b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.rightMargin = dp(6); lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
