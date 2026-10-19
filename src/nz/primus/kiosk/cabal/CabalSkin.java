// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Follows Voice Satellite and draws the CABAL skin. It draws only while its
 * skin is picked and the voice overlay shows: full screen as a portrait in the
 * top two thirds with the conversation in the bottom third, docked as a card at the bottom.
 * Every touch goes through. Text size and Reactive activity bar apply to both
 * looks. The backdrop is always solid black, so Theme and Background have no effect.
 */
final class CabalSkin {
    static final String KEY = "cabal_skin";
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.state", "voice.level", "voice.text", "voice.appearance"};
    static final String FACE = "cabal_face.jpg";
    /** Where the docked card sits above the bottom edge, in dp. */
    static final int DOCK_INSET = 24;
    /** How long the conversation takes to fade in or out, in ms. */
    static final int FADE_MS = 180;
    /** How long the conversation takes to glide up when the words outgrow their space, in ms. */
    static final int SCROLL_MS = 160;

    private PluginHost host;
    private int generation;
    private boolean selected;
    /** A voice.skin, voice.state or voice.appearance event arrived, which is newer than the read. */
    private boolean skinHeard, stateHeard, appearanceHeard;
    private String overlay = "hidden";
    /** What is drawn: null, "full" or "docked". */
    private String drawn;
    /** Read by the views on the main thread. */
    private volatile float level;
    private volatile int mode = CabalArt.IDLE;
    private volatile Portrait portrait;
    private volatile String command = "";
    private volatile String answer = "";
    private volatile String tool = "";
    private volatile String theme = "auto";
    /** Null for Skin default. */
    private volatile Float background;
    private volatile float textScale = 1f;
    private volatile boolean reactive = true;
    /** The showing view, so new words and settings reach it. Touched on the main thread only. */
    private SkinView view;

    synchronized void start(PluginHost host) {
        this.host = host;
        int current = ++generation;
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Kiosk Satellite versions without voice skins never list this skin.
            return;
        }
        loadPortrait(current);
        try {
            read(current);
        } catch (UnsupportedOperationException olderHost) {
            // Events alone still bring the skin up.
        }
    }

    /** Decodes the portrait and bakes the halo and jaw off the main thread. Without the picture the eyes still draw. */
    private void loadPortrait(int current) {
        PluginHost source = host;
        Thread thread = new Thread(() -> {
            Bitmap face = null;
            try (InputStream in = source.openAsset(FACE)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                face = BitmapFactory.decodeStream(in, null, options);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                source.log("CABAL could not read " + FACE + ": " + e.getMessage());
            }
            if (face == null) return;
            Portrait built = new Portrait(face);
            synchronized (CabalSkin.this) {
                if (current == generation) portrait = built;
            }
        }, "cabal-assist-portrait");
        thread.setDaemon(true);
        thread.start();
    }

    private void read(int current) {
        // Events win over these reads: they are newer.
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (CabalSkin.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> state = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(state.get("skinSelected"));
                if (!stateHeard && state.get("state") instanceof String) mode = modeOf((String) state.get("state"));
                if ("hidden".equals(overlay) && state.get("overlay") instanceof String) overlay = (String) state.get("overlay");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (CabalSkin.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (CabalSkin.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    static int modeOf(String state) {
        switch (state) {
            case "listening": return CabalArt.LISTENING;
            case "processing": return CabalArt.THINKING;
            case "responding": return CabalArt.SPEAKING;
            default: return CabalArt.IDLE;
        }
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("ks.voice.skin".equals(event)) {
            selected = Boolean.TRUE.equals(payload.get("selected"));
            skinHeard = true;
        } else if ("ks.voice.overlay".equals(event)) {
            overlay = String.valueOf(payload.get("overlay"));
        } else if ("ks.voice.state".equals(event)) {
            mode = modeOf(String.valueOf(payload.get("state")));
            stateHeard = true;
            SkinView current = view;
            if (current != null) current.post(current::refresh);
            return;
        } else if ("ks.voice.level".equals(event)) {
            Object value = payload.get("level");
            level = value instanceof Number ? ((Number) value).floatValue() : 0f;
            return;
        } else if ("ks.voice.text".equals(event)) {
            words(payload);
            return;
        } else if ("ks.voice.appearance".equals(event)) {
            appearanceHeard = true;
            appearance(payload);
            return;
        } else {
            return;
        }
        update();
    }

    float level() { return level; }
    int mode() { return mode; }
    boolean reactive() { return reactive; }
    Portrait portrait() { return portrait; }

    /** Whether the skin draws now, and how: null, "full" or "docked". */
    synchronized String drawn() { return drawn; }

    /** The Appearance settings as the skin reads them: theme, background, text scale and reactive. */
    synchronized Object[] appearance() { return new Object[] {theme, background, textScale, reactive}; }

    private void appearance(Map<?, ?> settings) {
        theme = settings.get("theme") instanceof String ? (String) settings.get("theme") : "auto";
        background = settings.get("background") instanceof Number ? ((Number) settings.get("background")).floatValue() : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1f;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? (String) text.get("command") : "";
        answer = text.get("answer") instanceof String ? (String) text.get("answer") : "";
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    /** Shows the skin while it is picked and the overlay is up, in the look that matches the overlay. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0f;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, OverlaySpec.FADE, 300), new Factory(false));
        } else {
            // A card at the bottom. Touches reach the dashboard around and under it.
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, OverlaySpec.SLIDE, 250), new Factory(true));
        }
    }

    private static OverlaySpec animated(OverlaySpec spec, String enter, String exit, int ms) {
        try {
            return spec.enter(enter).exit(exit).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = false;
        skinHeard = stateHeard = appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        level = 0f;
        mode = CabalArt.IDLE;
        portrait = null;
        command = answer = tool = "";
        theme = "auto";
        background = null;
        textScale = 1f;
        reactive = true;
    }

    private final class Factory implements OverlayFactory {
        private final boolean docked;

        Factory(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View changed, KsTheme theme) {
            ((SkinView) changed).restyle(theme);
        }

        @Override public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /**
     * A column that scrolls to the end of its text: when the words outgrow its height it glides up so the newest lines
     * stay in view and the oldest leave through the top. Given an exact height it keeps that height whatever the words,
     * and starts at the top until they overflow.
     */
    private static final class Tail extends FrameLayout {
        int maxHeight = Integer.MAX_VALUE;
        private float scrolled;
        private boolean laidOut;

        Tail(Context context) {
            super(context);
            setClipChildren(true);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            View child = getChildAt(0);
            child.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec)
                : child.getVisibility() == GONE ? 0 : Math.min(child.getMeasuredHeight(), maxHeight);
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            View child = getChildAt(0);
            int height = b - t, words = child.getMeasuredHeight();
            child.layout(0, 0, r - l, words);
            float target = words <= height ? 0f : height - words;
            if (target == scrolled && laidOut) return;
            scrolled = target;
            child.animate().cancel();
            if (laidOut && isAttachedToWindow()) child.animate().translationY(target).setDuration(SCROLL_MS).start();
            else child.setTranslationY(target);
            laidOut = true;
        }
    }

    /**
     * Full screen: a solid black backdrop with the portrait in the top two thirds of the screen and the conversation in
     * the bottom third. The two areas never change size, so the portrait stays put through listening, thinking and
     * speaking. Docked: a black card with the portrait beside the conversation.
     */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final CabalArt art;
        private final TextView said;
        private final TextView answered;
        private final BinaryStream stream;
        private final Tail tail;
        private final LinearLayout card;
        private KsTheme ksTheme;

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.ksTheme = theme;
            this.docked = docked;
            said = new TextView(context);
            answered = new TextView(context);
            stream = new BinaryStream(context);
            tail = new Tail(context);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            if (docked) {
                art = new CabalArt(context, CabalSkin.this, true);
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(art, new LinearLayout.LayoutParams(theme.px(76), theme.px(96)));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                said.setMaxLines(1);
                said.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                // The tail scrolls its one child, and the answer fades inside it: they must not share a view.
                LinearLayout block = new LinearLayout(context);
                block.setOrientation(LinearLayout.VERTICAL);
                block.addView(answered, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                tail.addView(block, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                words.addView(tail, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                words.addView(stream, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                wordsParams.leftMargin = theme.px(16);
                card.addView(words, wordsParams);
                int width = Math.min(theme.px(560), screenWidth - theme.px(48));
                addView(card, new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT));
            } else {
                art = new CabalArt(context, CabalSkin.this, false);
                card = null;
                said.setGravity(Gravity.CENTER);
                said.setMaxLines(2);
                said.setEllipsize(TextUtils.TruncateAt.END);
                answered.setGravity(Gravity.CENTER);
                // The portrait fills the top two thirds and the conversation the bottom third, whatever the words.
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                words.addView(said, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams answerLine = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                answerLine.topMargin = theme.px(8);
                words.addView(answered, answerLine);
                LinearLayout.LayoutParams streamLine = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                streamLine.gravity = Gravity.CENTER_HORIZONTAL;
                words.addView(stream, streamLine);
                tail.addView(words, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(LinearLayout.VERTICAL);
                column.addView(art, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 2f));
                LinearLayout.LayoutParams lower = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
                lower.topMargin = theme.px(12);
                column.addView(tail, lower);
                int side = theme.px(32);
                column.setPadding(side, theme.px(24), side, theme.px(32));
                addView(column, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            // Hidden until there are words, so the first refresh fades them in.
            said.setVisibility(GONE);
            said.setTag(false);
            answered.setVisibility(GONE);
            answered.setTag(false);
            stream.setVisibility(GONE);
            stream.setTag(false);
            if (docked) {
                tail.setVisibility(GONE);
                tail.setTag(false);
            }
            restyle(theme);
            refresh();
        }

        void restyle(KsTheme next) {
            ksTheme = next;
            restyle();
        }

        /** CABAL is drawn on black: a solid backdrop full screen and a black card docked. Text size scales every line. */
        void restyle() {
            float scale = textScale;
            if (docked) {
                GradientDrawable bubble = new GradientDrawable();
                bubble.setColor(Color.BLACK);
                bubble.setCornerRadius(ksTheme.px(ksTheme.radiusCard()));
                bubble.setStroke(Math.max(1, ksTheme.px(1) / 2), 0x33B7DFFF);
                card.setBackground(bubble);
                int pad = ksTheme.px(12);
                card.setPadding(pad, pad, ksTheme.px(20), pad);
                tail.maxHeight = Math.round(ksTheme.px(78) * scale);
                size(said, 13 * scale, 400);
                size(answered, 16 * scale, 500);
            } else {
                setBackgroundColor(Color.BLACK);
                size(said, 18 * scale, 400);
                size(answered, 26 * scale, 500);
            }
            stream.style(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, (docked ? 14 : 20) * scale, getResources().getDisplayMetrics()), docked ? Integer.MAX_VALUE : ksTheme.px(360));
            said.setTextColor(0xFF8FA9C2);
            answered.setTextColor(0xFFE6F2FF);
            art.setPlate(docked);
            tail.requestLayout();
            art.invalidate();
        }

        private void size(TextView view, float sp, int weight) {
            view.setTypeface(ksTheme.typeface(weight));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }

        /** The words fade in when they appear and out when they go. A line keeps its text while it fades out. */
        void refresh() {
            if (!command.isEmpty()) said.setText(command);
            fade(said, !command.isEmpty());
            String line = answer.isEmpty() ? tool : answer;
            if (!line.isEmpty()) answered.setText(line);
            fade(answered, !line.isEmpty());
            // Thinking: something was said, no answer or tool line is in yet.
            fade(stream, mode == CabalArt.THINKING && line.isEmpty());
            // Docked, the card shrinks to its words. Full screen the bottom third stays put.
            if (docked) fade(tail, !line.isEmpty());
        }

        /** The tag holds where the view is heading, so a repeat call with the same target changes nothing. */
        private void fade(View target, boolean show) {
            if (Boolean.valueOf(show).equals(target.getTag())) return;
            target.setTag(show);
            target.animate().cancel();
            if (show) {
                target.setAlpha(target.getVisibility() == VISIBLE ? target.getAlpha() : 0f);
                target.setVisibility(VISIBLE);
                target.animate().alpha(1f).setDuration(FADE_MS).start();
            } else {
                target.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                    if (!Boolean.TRUE.equals(target.getTag())) target.setVisibility(GONE);
                }).start();
            }
        }
    }
}
