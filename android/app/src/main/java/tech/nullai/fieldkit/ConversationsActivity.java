package tech.nullai.fieldkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;
import java.util.List;

/**
 * Conversations & Live Captions Hub for Spotter (Zoth FieldKit).
 *
 * <p>Designed for high-contrast legibility and effortless everyday use, with an expandable
 * Developer / Advanced Storage drawer at the bottom.
 */
public final class ConversationsActivity extends Activity {

    public static final String EXTRA_START_NEW_MISSION = "start_new_mission";
    public static final String EXTRA_ACTION = "conv_action";
    public static final String EXTRA_CONV_ID = "conv_id";
    public static final String ACTION_NEW_CHAT = "new_chat";
    public static final String ACTION_NEW_CALL = "new_call";
    public static final String ACTION_RESUME = "resume";

    private ConversationStore store;
    private LinearLayout listContainer;
    private Button btnCaptionsToggle;
    private TextView tvCaptionsSub;
    private LinearLayout devDrawerContent;
    private boolean devExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = ConversationStore.get(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF090D14);
        sv.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));

        // 1. Header row: Title + Back button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView title = new TextView(this);
        title.setText("Conversations");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFFFFFFF);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Start a new session or review past AI transcripts.");
        sub.setTextSize(13);
        sub.setTextColor(0xFFCBD5E1);
        titleCol.addView(sub);
        header.addView(titleCol);

        Button closeBtn = pillBtn("← Back", 0xFFF8FAFC, R.drawable.bg_dark_pill);
        closeBtn.setOnClickListener(v -> finish());
        header.addView(closeBtn);
        root.addView(header);

        // 2. Primary Action Row: 2 spacious buttons side-by-side (never wraps or clips)
        LinearLayout primaryRow = new LinearLayout(this);
        primaryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = dp(16);
        primaryRow.setLayoutParams(prLp);

        Button newMissionBtn = primaryActionBtn("＋ New Mission Chat", 0xFF34D399, R.drawable.bg_accent_emerald);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        leftLp.rightMargin = dp(6);
        newMissionBtn.setLayoutParams(leftLp);
        newMissionBtn.setOnClickListener(v -> promptNewConversation(false));
        primaryRow.addView(newMissionBtn);

        Button newCallBtn = primaryActionBtn("☎ New Muse Call", 0xFF7DD3FC, R.drawable.bg_dark_pill);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        rightLp.leftMargin = dp(6);
        newCallBtn.setLayoutParams(rightLp);
        newCallBtn.setOnClickListener(v -> promptNewConversation(true));
        primaryRow.addView(newCallBtn);
        root.addView(primaryRow);

        // 3. Digestible Live Captions Toggle Card
        LinearLayout ccCard = new LinearLayout(this);
        ccCard.setOrientation(LinearLayout.HORIZONTAL);
        ccCard.setGravity(Gravity.CENTER_VERTICAL);
        ccCard.setBackgroundResource(R.drawable.bg_dark_card);
        ccCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams ccCardLp = new LinearLayout.LayoutParams(-1, -2);
        ccCardLp.topMargin = dp(12);
        ccCard.setLayoutParams(ccCardLp);

        LinearLayout ccTextCol = new LinearLayout(this);
        ccTextCol.setOrientation(LinearLayout.VERTICAL);
        ccTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView ccTitle = new TextView(this);
        ccTitle.setText("Live AI Voice Captions (CC)");
        ccTitle.setTextSize(14.5f);
        ccTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ccTitle.setTextColor(0xFFFFFFFF);
        ccTextCol.addView(ccTitle);

        tvCaptionsSub = new TextView(this);
        tvCaptionsSub.setTextSize(12f);
        tvCaptionsSub.setTextColor(0xFFCBD5E1);
        ccTextCol.addView(tvCaptionsSub);
        ccCard.addView(ccTextCol);

        btnCaptionsToggle = pillBtn("", 0xFF34D399, R.drawable.bg_dark_pill);
        btnCaptionsToggle.setOnClickListener(v -> {
            boolean on = store.toggleCaptions();
            refreshCaptionsCard();
            Toast.makeText(this, on ? "Live AI Captions turned ON" : "Live AI Captions turned OFF", Toast.LENGTH_SHORT).show();
        });
        ccCard.addView(btnCaptionsToggle);
        root.addView(ccCard);
        refreshCaptionsCard();

        // 4. Section Label for Saved Threads
        TextView listHeader = new TextView(this);
        listHeader.setText("RECENT CONVERSATIONS");
        listHeader.setTextSize(11.5f);
        listHeader.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        listHeader.setTextColor(0xFF94A3B8);
        listHeader.setPadding(dp(2), dp(18), 0, dp(4));
        root.addView(listHeader);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        // 5. Collapsible Developer & Storage Drawer
        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCard.setBackgroundResource(R.drawable.bg_dark_subcard);
        devCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(-1, -2);
        devLp.topMargin = dp(22);
        devCard.setLayoutParams(devLp);

        TextView devToggle = new TextView(this);
        devToggle.setText("▸ Advanced & Developer Options");
        devToggle.setTextSize(13f);
        devToggle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        devToggle.setTextColor(0xFF94A3B8);
        devCard.addView(devToggle);

        devDrawerContent = new LinearLayout(this);
        devDrawerContent.setOrientation(LinearLayout.VERTICAL);
        devDrawerContent.setVisibility(View.GONE);
        devDrawerContent.setPadding(0, dp(10), 0, 0);

        TextView devInfo = new TextView(this);
        devInfo.setText("Storage: app-private files/conversations_v1.json\n"
                + "Gemini Live Audio Output Transcription: ENABLED\n"
                + "Active Thread ID: " + store.active().id);
        devInfo.setTextSize(11.5f);
        devInfo.setTypeface(Typeface.MONOSPACE);
        devInfo.setTextColor(0xFFCBD5E1);
        devDrawerContent.addView(devInfo);

        Button clearBtn = new Button(this);
        clearBtn.setText("Reset & Clear All Conversation History");
        clearBtn.setAllCaps(false);
        clearBtn.setTextSize(12.5f);
        clearBtn.setTextColor(0xFFFCA5A5);
        clearBtn.setBackgroundResource(R.drawable.bg_dark_pill);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-1, dp(44));
        clearLp.topMargin = dp(10);
        clearBtn.setLayoutParams(clearLp);
        clearBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear all conversations?")
                .setMessage("Deletes all saved conversation transcripts from this phone and starts a fresh thread.")
                .setPositiveButton("Clear all", (d, w) -> {
                    store.clearAll();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        devDrawerContent.addView(clearBtn);
        devCard.addView(devDrawerContent);

        devToggle.setOnClickListener(v -> {
            devExpanded = !devExpanded;
            devToggle.setText((devExpanded ? "▾ " : "▸ ") + "Advanced & Developer Options");
            devDrawerContent.setVisibility(devExpanded ? View.VISIBLE : View.GONE);
        });
        root.addView(devCard);

        sv.addView(root);
        setContentView(sv);
        render();
    }

    private void refreshCaptionsCard() {
        boolean on = store.isCaptionsEnabled();
        if (btnCaptionsToggle != null) {
            btnCaptionsToggle.setText(on ? "CC: ON" : "CC: OFF");
            btnCaptionsToggle.setTextColor(on ? 0xFF34D399 : 0xFF94A3B8);
            btnCaptionsToggle.setBackgroundResource(on ? R.drawable.bg_accent_emerald : R.drawable.bg_dark_pill);
        }
        if (tvCaptionsSub != null) {
            tvCaptionsSub.setText(on
                    ? "Showing live on-screen subtitles when Muse or Coach speaks."
                    : "Subtitles are hidden. Tap to turn on live captions.");
        }
    }

    private void promptNewConversation(boolean openCallImmediately) {
        final EditText input = new EditText(this);
        input.setHint(openCallImmediately
                ? "Topic for this call (optional)"
                : "What are you working on? (optional)");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(openCallImmediately ? "Start New Muse Live Call" : "Start New Mission Chat")
                .setMessage("Starts a fresh conversation thread so your next turns and AI captions stay organized.")
                .setView(input)
                .setPositiveButton(openCallImmediately ? "Start Live Call" : "Start Chat", (d, w) -> {
                    String title = input.getText().toString().trim();
                    String src = DatGlasses.INSTANCE.isStreaming() ? "Ray-Ban Meta" : "Phone Camera";
                    ConversationStore.Conversation c = store.startNew(
                            openCallImmediately ? "Muse Live Call" : "Mission Coach",
                            src,
                            title
                    );
                    if (!title.isEmpty()) {
                        store.appendTurn("Goal", title, c.mode, src);
                    }
                    Intent res = new Intent();
                    res.putExtra(EXTRA_CONV_ID, c.id);
                    res.putExtra(EXTRA_ACTION, openCallImmediately ? ACTION_NEW_CALL : ACTION_NEW_CHAT);
                    if (!title.isEmpty()) {
                        res.putExtra(EXTRA_START_NEW_MISSION, title);
                    }
                    setResult(RESULT_OK, res);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void render() {
        listContainer.removeAllViews();
        List<ConversationStore.Conversation> all = store.all();
        String activeId = store.active().id;

        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No conversations yet. Tap '＋ New Mission Chat' above to begin.");
            empty.setTextColor(0xFFE2E8F0);
            empty.setTextSize(14);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            listContainer.addView(empty);
            return;
        }

        for (ConversationStore.Conversation c : all) {
            listContainer.addView(buildCard(c, c.id.equals(activeId)));
        }
    }

    private View buildCard(ConversationStore.Conversation c, boolean isActive) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.bg_dark_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        // Top row: Title + Active Pill Badge
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(c.title);
        tvTitle.setTextSize(16.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvBadge = new TextView(this);
        tvBadge.setText(isActive ? "● ACTIVE" : (c.turns.size() + " turns"));
        tvBadge.setTextSize(11f);
        tvBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvBadge.setTextColor(isActive ? 0xFF34D399 : 0xFF38BDF8);
        tvBadge.setPadding(dp(8), dp(2), 0, dp(2));
        top.addView(tvBadge);
        card.addView(top);

        // Subtitle row: Mode + Camera Source + Timestamp
        TextView tvMeta = new TextView(this);
        tvMeta.setText(c.mode + "  ·  " + c.source + "  ·  " + formatTime(c.updatedAt));
        tvMeta.setTextSize(12f);
        tvMeta.setTextColor(0xFF94A3B8);
        tvMeta.setPadding(0, dp(4), 0, dp(8));
        card.addView(tvMeta);

        // Preview of last turn inside a dark sub-box for crisp readability
        TextView tvLast = new TextView(this);
        tvLast.setBackgroundResource(R.drawable.bg_dark_subcard);
        tvLast.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (!c.turns.isEmpty()) {
            ConversationStore.Turn last = c.turns.get(c.turns.size() - 1);
            String snippet = last.speaker + ": " + last.text;
            if (snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";
            tvLast.setText(snippet);
            tvLast.setTextColor(0xFFF1F5F9);
        } else {
            tvLast.setText("No messages recorded yet — tap Resume to talk in this conversation.");
            tvLast.setTextColor(0xFF94A3B8);
        }
        tvLast.setTextSize(13f);
        card.addView(tvLast);

        // Action Buttons Row: 4 evenly spaced buttons that never wrap or clip
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, 0);

        Button viewBtn = cardActionBtn("Transcript", 0xFFF8FAFC);
        viewBtn.setOnClickListener(v -> showTranscriptDialog(c));
        btns.addView(viewBtn, weightLp(4));

        Button resumeBtn = cardActionBtn(isActive ? "✓ Active" : "▶ Resume",
                isActive ? 0xFF34D399 : 0xFF7DD3FC);
        resumeBtn.setOnClickListener(v -> {
            store.setActiveId(c.id);
            Intent res = new Intent();
            res.putExtra(EXTRA_CONV_ID, c.id);
            res.putExtra(EXTRA_ACTION, ACTION_RESUME);
            setResult(RESULT_OK, res);
            Toast.makeText(this, "Switched to: " + c.title, Toast.LENGTH_SHORT).show();
            render();
        });
        btns.addView(resumeBtn, weightLp(4));

        Button shareBtn = cardActionBtn("Share", 0xFFFDE68A);
        shareBtn.setOnClickListener(v -> shareTranscript(c));
        btns.addView(shareBtn, weightLp(4));

        Button delBtn = cardActionBtn("Delete", 0xFFFCA5A5);
        delBtn.setOnClickListener(v -> {
            store.delete(c.id);
            render();
        });
        btns.addView(delBtn, weightLp(0));

        card.addView(btns);
        card.setOnClickListener(v -> showTranscriptDialog(c));
        return card;
    }

    private LinearLayout.LayoutParams weightLp(int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        if (rightMarginDp > 0) lp.rightMargin = dp(rightMarginDp);
        return lp;
    }

    private void showTranscriptDialog(ConversationStore.Conversation c) {
        String transcript = ConversationStore.formatTranscript(c);
        new AlertDialog.Builder(this)
                .setTitle(c.title + " (" + c.turns.size() + " turns)")
                .setMessage(transcript)
                .setPositiveButton("Use This Conversation", (d, w) -> {
                    store.setActiveId(c.id);
                    Intent res = new Intent();
                    res.putExtra(EXTRA_CONV_ID, c.id);
                    res.putExtra(EXTRA_ACTION, ACTION_RESUME);
                    setResult(RESULT_OK, res);
                    finish();
                })
                .setNeutralButton("Copy Text", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Spotter Transcript", transcript));
                        Toast.makeText(this, "Transcript copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void shareTranscript(ConversationStore.Conversation c) {
        String body = "# " + c.title + "\n"
                + "Mode: " + c.mode + " · Source: " + c.source + "\n\n"
                + ConversationStore.formatTranscript(c);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Spotter Transcript — " + c.title);
        send.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(send, "Share Conversation Transcript"));
    }

    private String formatTime(long ms) {
        Date d = new Date(ms);
        return DateFormat.getMediumDateFormat(this).format(d) + " "
                + DateFormat.getTimeFormat(this).format(d);
    }

    private Button primaryActionBtn(String label, int textColor, int bgRes) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(13f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setBackgroundResource(bgRes);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        return b;
    }

    private Button pillBtn(String label, int textColor, int bgRes) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(12.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setBackgroundResource(bgRes);
        return b;
    }

    private Button cardActionBtn(String label, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(2), dp(4), dp(2));
        b.setBackgroundResource(R.drawable.bg_dark_pill);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
