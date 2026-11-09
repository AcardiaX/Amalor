<div align="center">

# Amalor

**为 ColorOS 带来 Material You 配色、原生交互与更多定制选项**

Material 3 Expressive · Jetpack Compose · Modern Xposed

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](../LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/AcardiaX/Amalor?label=稳定版)](https://github.com/AcardiaX/Amalor/releases/latest)

**简体中文** | [English](README.en.md)

[下载](https://github.com/AcardiaX/Amalor/releases) · [反馈问题](https://github.com/AcardiaX/Amalor/issues)

</div>

Amalor 是一款面向 ColorOS 的独立 Xposed 模块，提供系统界面、系统桌面和微信输入法的定制功能，也有自己的配置界面。

**推荐与 [Material You for ColorOS（MYFC）](https://github.com/Acardia/Material-You-for-ColorOS) 搭配使用。** MYFC 提供系统级主题配色，Amalor 补充界面与交互调整；MYFC 是推荐搭配，而非安装或使用 Amalor 的前置条件。主页中的 MYFC 状态用于查看其安装情况。

## 功能

### 系统界面

- **控制中心与通知材质**：模糊 / 莫奈切换，系统动态颜色、标准圆角、Material 3 Expressive 滑条与磁贴点击反馈。
- **媒体卡片**：统一展开与收起的材质，调整媒体输出设备音量条的配色及文字对比度。
- **通知卡片**：主题化卡片与操作按钮，进度条使用 primary / secondaryContainer，支持双向滑动清除。
- **状态栏**：左右边距、网络类型显示位置；官方、系统、谷歌圆和 Pixel 网络图标样式；独立信号样式下隐藏非上网卡。
- **电池与磁贴图标**：规范电量进度，优化自动亮度图标裁剪，按需关闭磁贴图标动画。
- **原生特性**：AOSP 风格关机菜单、音量面板、剪贴板与返回手势，以及支持场景下的预测性返回动画。
- **应用更新等待页**：Material 3 Expressive 加载指示器，居中显示正在更新的应用图标。

### 系统桌面与输入法

- 自定义系统桌面的主屏页面。
- 在应用图标长按菜单中显示原生小组件入口。
- 通知圆点和数字角标跟随应用图标取色。
- 微信输入法键盘字体跟随系统字体。

### Amalor 界面

Material 3 Expressive 组件与动效、自适应导航栏、主题模式与调色板选择、日志等级设置。支持简体中文、English 和 Русский。

## 安装与使用

1. 使用 **Android 17（API 37）或更高版本**。当前主要适配目标为 ColorOS 17，具体功能取决于系统组件版本。
2. 安装支持 **Modern Xposed API 102** 的 [LSPosed](https://github.com/LSPosed/LSPosed) 或兼容框架。
3. 安装 Amalor，在框架中启用模块，并勾选所需作用域：

   | 作用域 | 功能 |
   | --- | --- |
   | `com.android.systemui` | 控制中心、通知、状态栏及原生特性 |
   | `com.android.launcher` | 系统桌面；返回桌面的预测性返回动画 |
   | `com.tencent.wetype` | 微信输入法键盘字体 |

4. 打开 Amalor 的「配置」，按应用选择功能。修改材质模式、网络类型等启动时生效的选项后，使用页面上的重启按钮重启对应作用域。

应用内重启按钮需要 Root 授权。返回手势页会一起重启系统界面与系统桌面；其余页面重启各自的目标应用。设置可先保存到本地，在框架服务连接后同步。

当前适配参考包括 SystemUI 17.99.02、系统桌面 17.3.12 / 17.5.4，以及微信输入法 4.0.1。反馈时请附设备型号、系统与目标应用版本、启用的选项和复现步骤。

## 构建

### 环境

| 项目 | 版本 |
| --- | --- |
| JDK | 25 |
| Gradle Wrapper | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | 2.4.10 |
| Android SDK | 37 |
| minSdk / targetSdk | 37 / 37 |

Miuix Navigation 通过 GitHub Packages 获取。在用户级 `~/.gradle/gradle.properties` 配置具有 `read:packages` 权限的凭据：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<Token>
```

也可使用环境变量 `GITHUB_ACTOR` 与 `GITHUB_TOKEN`。凭据保留在本地配置中。

**Windows / PowerShell：**

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

Linux / macOS 使用 `./gradlew`。默认版本名由 `gradle.properties` 中的 `VERSION_NAME` 指定，版本号来自 Git 提交数量；构建时可通过 `-PVERSION_NAME=1.2.0` 覆盖版本名。

安装包输出到 `app/build/outputs/apk/debug/` 或 `app/build/outputs/apk/release/`，文件名为 `Amalor_v<版本名>_<版本号>-<构建类型>.apk`。

正式签名在项目根目录的 `keystore.properties` 中配置：

```properties
storeFile=release.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

该文件及签名密钥已被 Git 忽略。缺少签名配置时，Release 构建使用 debug 签名。

## 源码结构

- `app/src/main/java/me/acardia/amalor/ui/`：配置页面、通用组件、导航与主题。
- `app/src/main/java/me/acardia/amalor/xposed/`：模块入口、共享偏好与日志。
- `xposed/systemui/`、`xposed/launcher/`、`xposed/wetype/`：按目标进程划分的功能实现。
- `app/src/main/resources/META-INF/xposed/`：Modern Xposed 入口、API 与作用域声明。

## 致谢

感谢以下项目的作者与贡献者。Amalor 使用了它们的代码、组件，或参考了其实现：

| 项目 | 在 Amalor 中的使用与参考 |
| --- | --- |
| [IntentX](https://github.com/wxxsfxyzm/IntentX) | 分组设置布局、连体按钮的组织与交互用法 |
| [ONextBox](https://github.com/MiToverG422/ONextBox) | 原生 SystemUI 特性、返回手势、桌面小组件入口与通知角标等功能移植参考 |
| [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) | 界面组件与应用内预测性返回转场参考 |
| [Material You for ColorOS](https://github.com/Acardia/Material-You-for-ColorOS) | 系统级 Material You 配色与推荐搭配 |
| [KernelSU](https://github.com/tiann/KernelSU) | Root 环境与权限状态识别参考 |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Compose 导航与转场 |
| [MaterialKolor](https://github.com/jordond/MaterialKolor) | 动态调色板与图标取色 |
| [libxposed](https://github.com/libxposed) | Modern Xposed API 与配置服务 |
| [AndroidX / Material 3](https://developer.android.com/jetpack/compose/designsystems/material3) | Compose、Material 3 Expressive 与 ButtonGroup 等组件 |

ButtonGroup 等 Material 组件由 AndroidX 提供；分组布局与组件用法的参考来源在上表中单独列出。移植代码保留源码中的来源说明，第三方依赖许可可在「关于 → 开放源代码许可」查看。

## 许可证

Amalor 采用 [GPL-3.0](../LICENSE) 许可证。第三方代码、图标与字体保留各自的版权及许可证。
