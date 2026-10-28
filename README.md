# SwiftClient for Android

Play **Minecraft: Java Edition** on your Android phone with SwiftClient: capes everyone can see,
the SwiftClient title screen and HUD mods, and SwiftSMP pinned in your server list.

Download: **<https://swiftclient.swiftsmp.net>** · Windows version on the
[Microsoft Store](https://apps.microsoft.com/detail/9P118N2JM63C).

You need your own Minecraft: Java Edition account (Microsoft sign-in). Android 8 or newer,
a 64-bit phone recommended.

## Getting started

1. Install the APK and sign in with Microsoft.
2. Pick a Minecraft version and tap **Play**. The first time, SwiftClient sets up Fabric, the
   SwiftClient mod, Fabric API and the performance pack in that version's own game folder; then
   Minecraft, Java and the libraries download from Mojang.

| Tab | What it does |
|---|---|
| **Play** | Live SwiftClient and SwiftSMP player counts, version picker, one Play button, Join SwiftSMP, controls, game folder, logs. Other profiles (vanilla, Forge…) still work underneath. |
| **Servers** | SwiftSMP, CyanRealms and ValenMC with live player counts and ping. **Join** launches straight into the server (Minecraft quick play). |
| **Mods** | Every mod in the version's folder with an on/off switch, **Add .jar** for your own Fabric mods (long-press to delete), **Update** to fetch the newest builds. |
| **Cloaks** | The SwiftClient cape catalog; tap one to wear it. Proved with Mojang's session join, like the Windows launcher. |

### Performance

- **Performance pack**: Lithium, FerriteCore, ModernFix and EntityCulling, newest build for the
  version from Modrinth (skipped when a mod has no build yet). Renderer replacements such as Sodium
  are left out on purpose: they break on Android's OpenGL translation layers.
- **Phone-tuned options** written once into a new instance: render distance 8, simulation 6, fast
  graphics, no clouds, fewer particles, no entity shadows, 60 FPS cap, no mipmaps or biome blend.
- **G1 GC tuned for short pauses** (`MaxGCPauseMillis=40`, string deduplication, explicit GC off) on
  SwiftClient profiles, unless you set your own collector.
- **Big-core affinity on by default**, so the game stays on the phone's fast cores.

The Right Shift menu and Zoom (C) need those keys: add them as buttons in **Custom controls**.

## What is SwiftClient's and what comes from Amethyst

SwiftClient for Android is built on [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android)
(a fork of [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)), which does all the
heavy lifting: the Android Java runtime, OpenGL translation, touch controls and Microsoft sign-in.
On top of it, this fork adds:

| Change | Where |
|---|---|
| New SwiftClient UI: header, Play / Servers / Mods / Cloaks tabs | `fragments/MainMenuFragment.java`, `res/layout/fragment_launcher.xml`, `item_sc_*.xml`, `values/swift_*.xml` |
| One-tap install: Fabric + SwiftClient mod (from the [SwiftClient release](https://github.com/SwiftSMP/SwiftClient/releases/latest)) + Fabric API + performance pack in `custom_instances/swiftclient-<version>`, one profile per version | `swiftclient/SwiftClientInstaller.java` |
| Live counters, server pings, capes, install heartbeat | `swiftclient/SwiftNet.java` |
| Cape server and SwiftSMP properties, GC tuning and quick-play join passed at launch | `Tools.java` |
| Microsoft accounts only (no offline accounts), big-core affinity on by default | `SelectAuthFragment.java`, `LauncherPreferences.java` |
| Name, package id (`net.swiftsmp.swiftclient.android`), icon and SwiftSMP colours | `app_pojavlauncher/build.gradle`, `res/` |

Upstream's own README is kept as [README.amethyst.md](README.amethyst.md).

## Building

Windows, JDK 17+ (tested with 25), the Android SDK; Gradle installs the NDK itself.

```bash
echo sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk > local.properties
git submodule update --init --depth 1
gradlew.bat :app_pojavlauncher:assembleRelease
```

The APK is `app_pojavlauncher/build/outputs/apk/release/app_pojavlauncher-release.apk`.
Release builds are signed with the key described by `~/.swiftclient-android/keystore.properties`
(or the file `SWIFTCLIENT_KEYSTORE` points to). **Back that key up**: Android only installs an
update signed with the same key.

## Licence

LGPL-3.0, like Amethyst and PojavLauncher (see [LICENSE](LICENSE)). The source of every
SwiftClient for Android build must stay available here. Minecraft is a trademark of Mojang;
SwiftClient is not affiliated with Mojang or Microsoft.
