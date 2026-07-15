# FlipOuterUnlock / MIX Flip 外屏解锁模块

> **`master`** — UI 分支（带 Compose 设置界面） | **`main`** — 稳定分支（纯 hook 无界面）
>
> Make the MIX Flip outer screen behave like a normal phone display.
> 让 MIX Flip 外屏像普通手机屏幕一样工作。

**一句话**：LSPosed 模块，去除外屏挖孔、全屏显示、解除应用限制、自由切换输入法、解除强制 Sogou 锁定、修复输入法工具栏、伪装设备身份。

**One-liner**: LSPosed module — removes outer screen cutout, forces fullscreen, unlocks apps, frees IME choice (no forced Sogou), enables landscape keyboard, fixes Sogou toolbar, spoofs device identity.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — unlock the outer display.

### Features

**Display & Fullscreen**
- Remove outer screen display cutout — clears camera hole-punch via `Display.getCutout()` zero-cutout injection
- Prevent cutout letterboxing — hooks `WindowState.isLetterboxedForDisplayCutout()` in system_server
- Force fullscreen for all apps — sets `layoutInDisplayCutoutMode=ALWAYS` on every Activity
- Fix app bounds on cold start and configuration changes

**Device Identity**
- Spoof device identity — hooks 7 detection paths: `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `miui.os.Build`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, `MiuiConfigs`. Excludes SystemUI (lock screen) and Sogou IME (keyboard height)
- Spoof screen type — hooks MIUI's `Configuration.getScreenType()` to return 0 (EXPAND)
- Scan orientation may vary by app — some require holding the camera side down before opening

**App Management**
- Whitelist all apps for continuity — uses `ContinuityPolicyService` dump injection
- Compat config injection — `ApplicationCompatManager` → `miui.continuity.policy=5`
- Remove app launch restrictions — `InterceptActivityController.isInterceptListUnCheckFold()` → false

**IME & Input**
- Enable IME in landscape — hooks `shouldShowCurrentInput()` → true
- Suppress rotation toast
- Unlock IME choice — hooks `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false, preventing forced Sogou switch on outer screen
- Sogou toolbar & clipboard fix — restores full keyboard layout on outer screen (uses DexKit)
- **Known issue**: Scan preview orientation varies by app. Some apps require holding the phone with the camera side down BEFORE opening the scan; others use their own camera logic and work regardless.

**SystemUI**
- Widget overlay disabled — 4-layer defense in fliphome process
- SystemUI-side widget suppression — hides decor window
- Notification menu fix — restores long-press menu via `isTinyScreen` scope faking
- Status bar clock hidden on outer screen
- Status bar icon expansion — shows up to 8 notification icons
- Always-On Display enabled on outer screen when folded (works, minor style issues)

### Hook Architecture

```
onSystemServerStarting (system_server):
├── DisplayStateHook          → dual-state: display=CLOSED + app=OPENED ★
├── CutoutHook.hookFramework  → Display.getCutout + Parser
├── LetterboxHook             → isLetterboxedForDisplayCutout → false
├── WhitelistHook             → ContinuityPolicyService dump
├── CompatConfigHook          → continuity.policy + PROPERTY_COMPAT
├── AppBoundsHook             → fillInsetsState + LaunchActivityItem
├── SystemServicesHook        → BoundsCompatUtils + getFullScreenValue
├── InputMethodHook           → shouldShowCurrentInput + isFlipTinyScreen
└── InterceptHook             → isInterceptListUnCheckFold

onPackageReady:
├── DeviceIdentityHook [* excl. SystemUI, Sogou]
├── CameraHook [camera]         → redirect front cam → main back (WIP)
├── CutoutHook [systemui, aod, camera]
├── SystemUIHook [systemui]   → widget, notification, clock, icons
├── SogouInputHook [sogou]    → toolbar + clipboard (DexKit)
├── ActivityLifecycleHook [*] → layoutInDisplayCutoutMode=ALWAYS
└── WatchOverlayHook [fliphome] → 4-layer widget defense

★ Sensor-layer dual-state split (experimental):
   LogicalDisplayMapper.setDeviceStateLocked → state=0 (outer screen on)
   ContinuityPolicyService.onDeviceStateChanged → false (unfolded)
   If stable, can replace DeviceIdentityHook + CompatConfigHook entirely.
```

### Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### Build

```bash
./gradlew :app:assembleDebug
```

### Release (signed)

Generate a keystore:
```bash
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip
```

Create `local.properties` (git-ignored):
```properties
androidStoreFile=flip.jks
androidStorePassword=<your-password>
androidKeyAlias=flip
androidKeyPassword=<your-password>
```

```bash
./gradlew :app:assembleRelease
```

For CI, add GitHub Secrets: `KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — reference for LSPosed architecture, SogouHook, DexKit usage, SystemUI hooks, and hook utilities
- Reverse engineering references in `refMD/cleaned/` (decompiled MIUI framework, services, fliphome APKs)

### TODO

- **GestureHook** — Keep fliphome gesture engine alive while disabling FlipLauncher (gestures not working)
- **SubScreenGestureHook** — Enable system-level multi-finger gestures on external display (no effect)
- **ScreenTypeHook** — Spoof `Configuration.getScreenType()` → 0 (inner-screen lockscreen breaks swipe-to-unlock)
- **LauncherDensityHook** — Lower density for inner launcher on outer screen (not working)
- **AodHook** — Enable AOD on outer screen when folded (flashing, can't turn off)
- **CameraHook** — Redirect front camera to main back on outer screen (not working — attempted F3.e, FUAbstractCamera, CameraManager.openCamera)
- **FaceUnlock** — Face unlock on outer screen (blocked — camera selection in vendor HAL daemon, not accessible from LSPosed)

### License

AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能

**显示与全屏**
- 移除挖孔：`Display.getCutout()` 零值注入 + `CutoutSpecification.Parser` 字段清零
- 防 letterboxing：`WindowState.isLetterboxedForDisplayCutout()` → false
- 全屏模式：所有 Activity 设置 `layoutInDisplayCutoutMode=ALWAYS`
- 修复冷启动与配置变更时 appBounds

**设备身份**
- 伪装设备类型：hook 7 条检测路径（`MiuiMultiDisplayTypeInfo.isFlipDevice()`、`miui.os.Build`、`miuix.os.Build.IS_FLIP`、`DeviceUtils`、`DeviceHelper`、`MiuiConfigs`）。排除 SystemUI（锁屏）和 Sogou 输入法（键盘高度）
- 伪装屏幕类型：`Configuration.getScreenType()` → 0
- 扫一扫预览方向因应用而异——部分需在打开前以摄像头侧为底

**应用管理**
- 所有应用白名单注入
- 兼容配置注入：`miui.continuity.policy=5`
- 移除应用启动拦截

**输入法**
- 横屏键盘启用 + 禁旋转提示
- 解除输入法锁定 — hook `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false，阻止外屏强制切 Sogou
- Sogou 工具栏+剪贴板修复（DexKit）
- **已知问题**：扫一扫预览方向因应用而异。部分应用需在**点击扫一扫前**以靠近摄像头一侧为底才能正常显示；部分应用走自带逻辑无需调整

**SystemUI**
- Widget 覆盖层 4 层禁用
- SystemUI 侧 widget 隐藏
- 通知菜单修复
- 外屏状态栏时钟隐藏
- 通知图标扩展到 8 个
- 折叠状态下外屏 AOD 启用（已可用，样式有小问题）

### 要求

- LSPosed（libxposed API 101+）
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### 构建与签名

```bash
# 生成密钥
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip

# local.properties
androidStoreFile=flip.jks
androidStorePassword=<密码>
androidKeyAlias=flip
androidKeyPassword=<密码>

# 签名构建
./gradlew :app:assembleRelease
```

CI: GitHub Secrets → `KEYSTORE`(base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、SogouHook、DexKit、SystemUI hook 及工具类参考
- `refMD/cleaned/` — MIUI 框架及 fliphome 反编译参考文档

### 未完成

- **GestureHook** — 禁用 FlipLauncher 同时保活 fliphome 手势引擎（手势不生效）
- **SubScreenGestureHook** — 启用系统级外屏多指手势（无效果）
- **ScreenTypeHook** — 伪装 `Configuration.getScreenType()` → 0（内屏样式锁屏无法上滑解锁）
- **LauncherDensityHook** — 降低内屏桌面在外屏的 density 以改善布局（不生效）
- **AodHook** — 折叠状态下外屏 AOD（闪烁、无法关闭）
- **CameraHook** — 外屏前摄重定向到主后摄（不生效 — 已尝试 F3.e / FUAbstractCamera / CameraManager.openCamera 三层 hook）
- **FaceUnlock** — 外屏人脸解锁（已确认不可行 — 相机选择在 vendor HAL daemon 内部，LSPosed 无法触及）

### License

AGPL-3.0
