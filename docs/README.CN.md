<div align="center">

# Amalor

**[Material You for ColorOS](https://github.com/Acardia/Material-You-for-ColorOS) 的配套管理应用**

Material 3 Expressive · Compose · Root 模块配置

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](../LICENSE)
[![Release](https://img.shields.io/badge/version-1.0.0-green.svg)](#)

[English](README.md) | **简体中文**

</div>


## 功能


**配置**
- 信号图标：单卡 / 双卡
- 系统界面：模糊 / 莫奈
- 通知卡片：默认宽度 / 减小宽度

**界面**
- Material 3 Expressive 动效与组件
- AOSP 预测性返回手势转场
- 点击触感反馈
- 启动页动画
- 界面语言：English / 简体中文 / Русский

## 系统要求

- Android 12（API 31）及以上
- 已获取 Root 权限
- 已安装 Material You for ColorOS 模块

## 构建

### 环境

| 项目 | 版本 |
| --- | --- |
| JDK | 25 |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | 2.4.10 |
| compileSdk | 37 |
| minSdk / targetSdk | 31 / 36 |

无需手动安装 Gradle，使用 Wrapper 即可。

### GitHub Packages 凭据

本项目依赖 Miuix Navigation，它发布在 GitHub Packages 上。构建前需要提供
只读令牌，否则无法解析依赖。

在**用户级** `~/.gradle/gradle.properties`（不要写进项目文件）中添加：

```properties
gpr.user=<你的 GitHub 用户名>
gpr.key=<具有 read:packages 权限的 Token>
```

也可以改用环境变量 `GITHUB_ACTOR` 与 `GITHUB_TOKEN`。

> Token 属于机密信息，请勿提交到仓库。

### Debug 构建

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Linux / macOS 使用 `./gradlew`。

### Release 构建

在项目根目录创建 `keystore.properties`（该文件已被 `.gitignore` 忽略）：

```properties
storeFile=release.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

然后执行：

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

没有配置 `keystore.properties` 时，Release 会回退使用 debug 签名。

## 项目结构

```text
app/src/main/java/me/acardia/amalor/
├── AmalorApp.kt            主导航、Pager、Root/模块状态
├── MainActivity.kt         主题与启动
├── SettingsStore.kt        DataStore 设置持久化
└── ui/
    ├── home/               主页
    ├── config/             模块配置
    ├── settings/           个性化设置
    ├── about/              关于与开源许可证
    ├── navigation/         MainPagerState 快速导航
    ├── animation/          AOSP 预测性返回转场
    ├── component/          通用组件
    └── theme/              主题与调色板
```

## 致谢

Amalor 在开发过程中参考了以下开源项目或使用了其代码，在此表示感谢：

- [InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)
- [KernelSU](https://github.com/tiann/KernelSU)
- [Miuix](https://github.com/compose-miuix-ui/miuix) 
- [MaterialKolor](https://github.com/jordond/MaterialKolor) 

第三方依赖的各自许可证可在应用内 **关于 → 开放源代码许可** 中查看。

## 许可证

本项目采用 [GPL-3.0](../LICENSE) 许可证。

依赖库保留其各自的许可证，详见应用内开源许可页面。
