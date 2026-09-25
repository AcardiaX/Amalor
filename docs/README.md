<div align="center">

# Amalor

**Companion management app for [Material You for ColorOS](https://github.com/Acardia/Material-You-for-ColorOS)**

Material 3 Expressive · Compose · Root module configuration

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](../LICENSE)
[![Release](https://img.shields.io/badge/version-1.0.0-green.svg)](#)

**English** | [简体中文](README.CN.md)

</div>


## Features


**Configuration**
- Signal icon: Single SIM / Dual SIM
- System UI: Blur / Monet
- Notification card: Default width / Reduced width

**Interface**
- Material 3 Expressive motion and components
- AOSP predictive back gesture transitions
- Tap haptic feedback
- Splash screen animation
- UI languages: English / Simplified Chinese / Russian

## Requirements

- Android 12 (API 31) or later
- Root access granted
- Material You for ColorOS module installed

## Building

### Environment

| Item | Version |
| --- | --- |
| JDK | 25 |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | 2.4.10 |
| compileSdk | 37 |
| minSdk / targetSdk | 31 / 36 |

No manual Gradle installation is required; use the Wrapper.

### GitHub Packages credentials

This project depends on Miuix Navigation, which is published on GitHub Packages.
A read-only token is required before building, otherwise the dependency cannot
be resolved.

Add the following to your **user-level** `~/.gradle/gradle.properties`
(do not put it in project files):

```properties
gpr.user=<your GitHub username>
gpr.key=<a token with read:packages permission>
```

Alternatively, use the environment variables `GITHUB_ACTOR` and `GITHUB_TOKEN`.

> Tokens are secrets. Never commit them to the repository.

### Debug build

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Use `./gradlew` on Linux / macOS.

### Release build

Create `keystore.properties` in the project root
(this file is already ignored by `.gitignore`):

```properties
storeFile=release.jks
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

Then run:

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

Without `keystore.properties`, the Release build falls back to the debug
signing config.

## Project structure

```text
app/src/main/java/me/acardia/amalor/
├── AmalorApp.kt            Main navigation, Pager, root/module state
├── MainActivity.kt         Theme and splash
├── SettingsStore.kt        DataStore settings persistence
└── ui/
    ├── home/               Home
    ├── config/             Module configuration
    ├── settings/           Personalization settings
    ├── about/              About and open source licenses
    ├── navigation/         MainPagerState fast navigation
    ├── animation/          AOSP predictive back transitions
    ├── component/          Shared components
    └── theme/              Theme and palettes
```

## Acknowledgements

Amalor references or uses code from the following open source projects:

- [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)
- [KernelSU](https://github.com/tiann/KernelSU)
- [Miuix](https://github.com/compose-miuix-ui/miuix)
- [MaterialKolor](https://github.com/jordond/MaterialKolor)

Licenses for third-party dependencies can be found in the app under
**About → Open source licenses**.

## License

This project is licensed under [GPL-3.0](../LICENSE).

Dependencies retain their own licenses; see the in-app open source license page.
