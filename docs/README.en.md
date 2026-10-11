<div align="center">

# Amalor

**Companion management app for [Material You for ColorOS](https://github.com/Acardia/Material-You-for-ColorOS)**

Material 3 Expressive · Compose · LSPosed module configuration

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](../LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/AcardiaX/Amalor)](https://github.com/AcardiaX/Amalor/releases/latest)

[简体中文](README.md) | **English**

</div>


## Features

**System UI**
- System UI style: Blur / Monet
- Mobile network type: display position, weight, and font
- Font style: Official / System / Google Rounded / Custom
- Normalized battery display: keep the battery progress inside the body
- M3E style for the software update page
- One-tap System UI restart

**Interface**
- Material 3 Expressive motion and components
- Adaptive navigation rail for large screens
- AOSP predictive back gesture transitions
- Tap haptic feedback
- Splash screen animation
- UI languages: Simplified Chinese / English / Russian

## Requirements

- Android 17 (API 37) or later
- Root access granted
- [LSPosed](https://github.com/LSPosed/LSPosed) installed (provides the LibXposed service)
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
| minSdk / targetSdk | 37 / 37 |

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
app/build/outputs/apk/debug/Amalor_v<version>_<code>-debug.apk
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
├── AmalorApp.kt            Main navigation, Pager, settings sync
├── MainActivity.kt         Theme and splash
├── AmalorApplication.kt    Xposed service binding and state publishing
├── xposed/                 LSPosed module entry and SystemUI hooks
├── ui/
│   ├── home/               Home
│   ├── config/             System UI configuration screens
│   ├── settings/           Personalization settings
│   ├── about/              About and open source licenses
│   ├── navigation/         Routes and MainPagerState fast navigation
│   ├── component/          Shared components
│   └── theme/              Theme and palettes
└── ...

app/src/main/resources/META-INF/xposed/
├── java_init.list          Module entry declaration
├── module.prop             LibXposed API version and options
└── scope.list              Scope (com.android.systemui)
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
