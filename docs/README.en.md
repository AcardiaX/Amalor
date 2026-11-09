<div align="center">

# Amalor

**Material You colors, native interactions, and more customization for ColorOS**

Material 3 Expressive · Jetpack Compose · Modern Xposed

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](../LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/AcardiaX/Amalor)](https://github.com/AcardiaX/Amalor/releases/latest)

[简体中文](README.md) | **English**

[Download](https://github.com/AcardiaX/Amalor/releases) · [Report an issue](https://github.com/AcardiaX/Amalor/issues)

</div>

Amalor is a standalone Xposed module for ColorOS, with customization for System UI, the system launcher, and WeType, alongside its own configuration app.

**We recommend using it with [Material You for ColorOS (MYFC)](https://github.com/Acardia/Material-You-for-ColorOS).** MYFC provides system-wide theme colors, while Amalor adds interface and interaction changes. MYFC is a recommended companion, not a prerequisite for installing or using Amalor. The MYFC status on the home page reports whether it is installed.

## Features

### System UI

- **Quick Settings and notification materials:** Blur / Monet modes, system dynamic colors, circular corners, Material 3 Expressive sliders, and tile tap feedback.
- **Media cards:** Consistent expansion and collapse materials, plus readable volume tracks and labels in the output-device panel.
- **Notification cards:** Themed cards and action buttons, primary / secondaryContainer progress bars, and swipe dismissal in both directions.
- **Status bar:** Left and right margins, network-type positioning, Official / System / Google Rounded / Pixel styles, and hiding the non-data SIM in the independent signal layout.
- **Battery and tile icons:** Normalized battery fill, automatic-brightness icon clipping adjustments, and optional tile-icon animation removal.
- **Native features:** AOSP-style power menu, volume panel, clipboard, and back gestures, including predictive back animations in supported navigation scenarios.
- **App-update waiting screen:** A Material 3 Expressive loading indicator with the updating app's icon centered inside it.

### Launcher and keyboard

- Choose the system launcher's home page.
- Add the native widgets entry to app-icon long-press menus.
- Derive notification-dot and number-badge colors from each app icon.
- Make WeType's keyboard follow the system font.

### Amalor interface

Material 3 Expressive components and motion, adaptive navigation, theme and palette selection, and configurable logging. Available in Simplified Chinese, English, and Russian.

## Installation and use

1. Use **Android 17 (API 37) or later**. ColorOS 17 is the primary adaptation target; individual features depend on system-component versions.
2. Install [LSPosed](https://github.com/LSPosed/LSPosed) or a compatible framework supporting **Modern Xposed API 102**.
3. Install and enable Amalor in the framework, then select the scopes you need:

   | Scope | Features |
   | --- | --- |
   | `com.android.systemui` | Quick Settings, notifications, status bar, and native features |
   | `com.android.launcher` | Launcher customization and return-home predictive back |
   | `com.tencent.wetype` | WeType keyboard font |

4. Open **Configuration** in Amalor and select features by app. After changing startup-applied settings, such as material mode or network-type styling, restart the relevant scope using its page's restart button.

In-app restart buttons require Root authorization. The back-gesture page restarts both System UI and the launcher; other pages restart their respective target apps. Settings can be saved locally and synchronized when the framework service connects.

Current adaptation references include SystemUI 17.99.02, launcher 17.3.12 / 17.5.4, and WeType 4.0.1. When reporting an issue, include your device, system and target-app versions, enabled options, and reproduction steps.

## Building

### Environment

| Item | Version |
| --- | --- |
| JDK | 25 |
| Gradle Wrapper | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | 2.4.10 |
| Android SDK | 37 |
| minSdk / targetSdk | 37 / 37 |

Miuix Navigation is distributed through GitHub Packages. Set credentials with `read:packages` access in your user-level `~/.gradle/gradle.properties`:

```properties
gpr.user=<GitHub username>
gpr.key=<Token>
```

Alternatively, use `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables. Keep credentials in local configuration.

**Windows / PowerShell:**

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

Use `./gradlew` on Linux / macOS. The default version name is set by `VERSION_NAME` in `gradle.properties`, and the version code comes from the Git commit count. Pass `-PVERSION_NAME=1.2.0` to override the version name for a build.

APKs are written to `app/build/outputs/apk/debug/` or `app/build/outputs/apk/release/`, named `Amalor_v<version>_<code>-<build-type>.apk`.

Configure release signing in `keystore.properties` at the project root:

```properties
storeFile=release.jks
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

The configuration file and signing keys are Git-ignored. Without signing configuration, Release builds use the debug key.

## Source layout

- `app/src/main/java/me/acardia/amalor/ui/`: Configuration screens, shared components, navigation, and themes.
- `app/src/main/java/me/acardia/amalor/xposed/`: Module entry, shared preferences, and logging.
- `xposed/systemui/`, `xposed/launcher/`, and `xposed/wetype/`: Feature implementations grouped by target process.
- `app/src/main/resources/META-INF/xposed/`: Modern Xposed entry, API, and scope declarations.

## Acknowledgements

Thanks to the authors and contributors of these projects. Amalor uses their code or components, or references their implementations:

| Project | Use or reference in Amalor |
| --- | --- |
| [IntentX](https://github.com/wxxsfxyzm/IntentX) | Grouped settings layouts and connected-button organization and interactions |
| [ONextBox](https://github.com/MiToverG422/ONextBox) | Native System UI features, back gestures, launcher widgets, and notification-badge feature ports |
| [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) | UI components and in-app predictive navigation transitions |
| [Material You for ColorOS](https://github.com/Acardia/Material-You-for-ColorOS) | System-wide Material You colors and recommended companion setup |
| [KernelSU](https://github.com/tiann/KernelSU) | Root-environment and authorization-status detection references |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Compose navigation and transitions |
| [MaterialKolor](https://github.com/jordond/MaterialKolor) | Dynamic palettes and icon-based colors |
| [libxposed](https://github.com/libxposed) | Modern Xposed API and configuration service |
| [AndroidX / Material 3](https://developer.android.com/jetpack/compose/designsystems/material3) | Compose, Material 3 Expressive, ButtonGroup, and other components |

Material components such as ButtonGroup are provided by AndroidX; references for the surrounding layouts and usage patterns are listed separately above. Ported code retains its source attribution. Dependency licenses are available under **About → Open source licenses**.

## License

Amalor is licensed under [GPL-3.0](../LICENSE). Third-party code, icons, and fonts retain their respective copyrights and licenses.
