// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

public final class CabalSkinTest {
    static final class Host implements PluginHost {
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, CommandCallback> reads = new HashMap<>();
        final Map<String, OverlaySpec> overlays = new HashMap<>();
        boolean older;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) { reads.put(command, callback); }
        public void subscribe(String event) { if (older) throw new IllegalArgumentException("Unknown KS event"); subscriptions.add(event); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { assert key.matches("[a-z][a-z0-9_]{0,39}") : key; overlays.put(key, spec); }
        public void hideOverlay(String key) { overlays.remove(key); }
        public java.io.InputStream openAsset(String name) throws java.io.IOException { throw new java.io.IOException("no assets in tests"); }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) {}
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    public static void main(String[] args) {
        // The mesh keeps the upper lip still and lets the chin follow.
        assert Portrait.follow(715f, 800f) == 0f;
        assert Portrait.follow(715f, 880f) == 1f;
        assert Portrait.follow(715f, 1008f) == 0f;
        assert Portrait.follow(715f + 250f, 880f) == 0f;
        assert CabalSkin.modeOf("responding") == CabalArt.SPEAKING && CabalSkin.modeOf("processing") == CabalArt.THINKING;

        Host host = new Host();
        CabalSkin skin = new CabalSkin();
        skin.start(host);
        assert host.subscriptions.containsAll(Arrays.asList(CabalSkin.EVENTS));
        assert host.reads.containsKey("getVoiceState") && host.reads.containsKey("getVoiceText") && host.reads.containsKey("getVoiceAppearance");

        // Not picked: the turn is KS's to draw.
        host.reads.get("getVoiceState").onResult(true, map("enabled", true, "state", "idle", "skinSelected", false, "overlay", "hidden"), null);
        skin.onEvent("ks.voice.overlay", map("overlay", "full"));
        assert host.overlays.isEmpty() && skin.drawn() == null;

        // Picked: full screen, on top, touches pass through.
        skin.onEvent("ks.voice.skin", map("selected", true));
        OverlaySpec full = host.overlays.get(CabalSkin.KEY);
        assert full != null && full.onTop() && !full.touchable() && "full".equals(skin.drawn());
        skin.onEvent("ks.voice.state", map("state", "responding"));
        skin.onEvent("ks.voice.level", map("level", 0.5));
        assert skin.mode() == CabalArt.SPEAKING && skin.level() == 0.5f;

        // Docked replaces the full look, and back.
        skin.onEvent("ks.voice.overlay", map("overlay", "docked"));
        OverlaySpec docked = host.overlays.get(CabalSkin.KEY);
        assert docked != null && docked != full && docked.onTop() && !docked.touchable() && "docked".equals(skin.drawn());
        skin.onEvent("ks.voice.overlay", map("overlay", "full"));
        assert host.overlays.get(CabalSkin.KEY) != docked;
        skin.onEvent("ks.voice.overlay", map("overlay", "hidden"));
        assert host.overlays.isEmpty() && skin.level() == 0f;

        // It follows the Appearance settings.
        skin.onEvent("ks.voice.appearance", map("theme", "dark", "background", 0.4, "textScale", 1.5, "reactive", false));
        Object[] appearance = skin.appearance();
        assert "dark".equals(appearance[0]) && Float.valueOf(0.4f).equals(appearance[1]);
        assert Float.valueOf(1.5f).equals(appearance[2]) && Boolean.FALSE.equals(appearance[3]) && !skin.reactive();
        skin.onEvent("ks.voice.appearance", map("theme", "auto", "background", null, "textScale", 1.0, "reactive", true));
        assert skin.appearance()[1] == null && skin.reactive();

        // Another skin picked mid turn: it steps aside.
        skin.onEvent("ks.voice.overlay", map("overlay", "full"));
        skin.onEvent("ks.voice.skin", map("selected", false));
        assert host.overlays.isEmpty();

        // A read that lands after the events does not undo them.
        Host late = new Host();
        CabalSkin second = new CabalSkin();
        second.start(late);
        second.onEvent("ks.voice.skin", map("selected", true));
        second.onEvent("ks.voice.overlay", map("overlay", "full"));
        late.reads.get("getVoiceState").onResult(true, map("skinSelected", false, "overlay", "hidden", "state", "idle"), null);
        assert late.overlays.containsKey(CabalSkin.KEY);

        // Older KS versions do not know the events.
        Host older = new Host();
        older.older = true;
        CabalSkin third = new CabalSkin();
        third.start(older);
        assert older.reads.isEmpty();
        third.onEvent("ks.voice.skin", map("selected", true));
        third.onEvent("ks.voice.overlay", map("overlay", "full"));

        skin.stop();
        skin.onEvent("ks.voice.skin", map("selected", true));
        assert skin.drawn() == null;
        System.out.println("CabalSkinTest passed");
    }
}
