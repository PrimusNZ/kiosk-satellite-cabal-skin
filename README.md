# CABAL

<p align="center">
  <img src="assets/voice-skin.webp" alt="CABAL voice skin" width="640" />
</p>

CABAL, the Brotherhood of Nod's command-and-control AI, now runs your smart home. A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that adds the **CABAL** skin to Voice Satellite.

## Install

Requires Android 7 (API 24) and Kiosk Satellite 2026.10.30 as a minimum.

1. Download the latest `primusnz-cabal-skin-<version>.zip` from the **Releases** page.
2. Open **Plugin Manager > Developer Tools > Install from ZIP** on the kiosk or in Remote Admin.
3. Select the ZIP, confirm you trust the code and enable the plugin.
4. Pick **CABAL** in **Voice Satellite > Appearance > Skin**.

## Build from source

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

The ZIP and its `.sha256` are written to `dist/`. Install the ZIP as described above.

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
