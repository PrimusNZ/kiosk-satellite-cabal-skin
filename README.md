# CABAL

<p align="center">
  <img src="assets/voice-skin.webp" alt="CABAL voice skin" width="640" />
</p>
CABAL, Formally the Brotherhood of Nod's command-and-control AI, he now runs your smart home.
A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that adds the **CABAL** skin to Voice Satellite.

## Installation

### Community Plugins (Recommended)

My Plugin is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=PrimusNZ/kiosk-satellite-cabal-skin)

Or search for `My Plugin` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/PrimusNZ/kiosk-satellite-cabal-skin` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Configuration

1. Open **Settings > Plugin Manager** and ensure the CABAL plugin is enabled.
2. Pick **CABAL** in **Voice Satellite > Appearance > Skin** page.
3. Configure Voice Satellite as you would any other built-in skin.

## Building from source

You need Python 3, JDK 17 or newer and an Android SDK with build-tools and at least one platform. This repo holds only the plugin's own source, so the build tool and SDK interfaces come from the [plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world). Keep them in an untracked `.build-kit/` folder, which `.gitignore` already excludes.

```sh
git clone <this repository> && cd <this repository>

# Fetch the template's build tool and SDK interfaces into .build-kit/
git clone --depth 1 https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world /tmp/ks-plugin-template
mkdir .build-kit && cp -r /tmp/ks-plugin-template/tools /tmp/ks-plugin-template/sdk .build-kit/

# Build
export ANDROID_HOME=/path/to/android-sdk   # an absolute path, not ~
python3 .build-kit/tools/build.py .
```

The ZIP and its `.sha256` are written to `dist/`.

- `--android-platform <version>` picks an installed platform. The default is the highest installed.
- `--version 1.2.3` overrides the version in `kiosk-satellite-plugin.json`.

To run the lifecycle test against the Android stubs:

```sh
PLATFORM=$(ls -d $ANDROID_HOME/platforms/android-*/android.jar | sort -V | tail -1)
javac --release 8 -cp $PLATFORM -d /tmp/cabal-test $(find .build-kit/sdk/src src tests -name '*.java')
java -ea -cp /tmp/cabal-test:$PLATFORM nz.primus.kiosk.cabal.CabalSkinTest
```

## License

MIT. See [LICENSE](LICENSE).
