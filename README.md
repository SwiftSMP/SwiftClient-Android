# SwiftClient for Android

Play **Minecraft: Java Edition** on your Android phone with SwiftClient: capes everyone can see,
the SwiftClient title screen and HUD mods, and SwiftSMP pinned in your server list.

Download: **<https://swiftclient.swiftsmp.net>** · Windows version on the
[Microsoft Store](https://apps.microsoft.com/detail/9P118N2JM63C).

You need your own Minecraft: Java Edition account (Microsoft sign-in). Android 8 or newer,
a 64-bit phone recommended.

## Getting started

1. Install the APK and sign in with Microsoft.
2. Tap **Install SwiftClient** and pick a Minecraft version. SwiftClient sets up Fabric, the
   SwiftClient mod and Fabric API in their own game folder and selects the new profile.
3. Tap **Play**. Minecraft, Java and the libraries are downloaded from Mojang the first time.

The Right Shift menu and Zoom (C) need those keys: add them as buttons in **Custom controls**.

## What is different from Amethyst

SwiftClient for Android is built on [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android)
(a fork of [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)), which does all the
heavy lifting: the Android Java runtime, OpenGL translation, touch controls and Microsoft sign-in.
On top of it, this fork adds:

| Change | Where |
|---|---|
| **Install SwiftClient** button: Fabric + SwiftClient mod (from the [SwiftClient release](https://github.com/SwiftSMP/SwiftClient/releases/latest)) + Fabric API (from Modrinth) in `custom_instances/swiftclient-<version>`, one profile per version | `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/swiftclient/SwiftClientInstaller.java`, `fragments/MainMenuFragment.java` |
| SwiftClient cape server and SwiftSMP server passed to the game whenever the mod is installed | `Tools.java` (`SwiftClientInstaller.JVM_PROPERTIES`) |
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
