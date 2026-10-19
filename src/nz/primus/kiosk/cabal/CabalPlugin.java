// SPDX-License-Identifier: MIT
package nz.primus.kiosk.cabal;

import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** The CABAL voice skin. It has no settings: Voice Satellite's Appearance settings drive it. */
public final class CabalPlugin implements KioskPlugin {
    private final CabalSkin skin = new CabalSkin();

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        host.log("CABAL started");
        skin.start(host);
    }

    @Override public void configure(Map<String, Object> settings) {}

    @Override public void execute(String command, Map<String, Object> arguments) {}

    @Override public void onEvent(String event, Map<String, Object> payload) {
        skin.onEvent(event, payload);
    }

    @Override public void stop() { skin.stop(); }
}
