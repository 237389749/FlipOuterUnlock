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
- System gestures (back) — blocks fliphome FlipLauncher, keeps GestureStubView edge back
- System gestures (home/recents) — fixes miuihome NavStubView 3-gate: fold removal, hideGestureLine, default-home check
- Always-On Display enabled on outer screen when folded (v2.3 — screen state fix)
- Front camera redirect to main back camera (v2 — dynamic LENS_FACING enumeration)
- Sub-screen double-tap + 3-finger swipe gestures (displayId fix for state=6)
- Feature toggles — SystemProperties-based, reboot-required, no UI

### Feature Toggles

All features can be disabled via `setprop`. Changes take effect after reboot. No UI — by design.

```bash
# List current settings
getprop | grep persist.flipunlock

# Disable a feature (example)
setprop persist.flipunlock.ui.widget false
reboot

# Re-enable
setprop persist.flipunlock.ui.widget true
reboot
```

| Property | Default | Controls |
|----------|---------|----------|
| `persist.flipunlock.enable` | true | **Master kill switch** — false disables everything |
| `persist.flipunlock.display.dual` | true | Dual display (DisplayStateHook: force state=6, display enable) |
| `persist.flipunlock.display.aod` | true | Outer screen AOD |
| `persist.flipunlock.display.cutout` | true | Remove cutout + letterbox + appBounds + lifecycle |
| `persist.flipunlock.gesture.home` | true | Bottom gestures: home/recents (LauncherHook) |
| `persist.flipunlock.gesture.back` | true | Back gestures: disable FlipLauncher (GestureHook) |
| `persist.flipunlock.ui.lockscreen` | true | Lock screen large layout (LockScreenHook) |
| `persist.flipunlock.ui.widget` | true | Disable widget overlay (WatchOverlayHook) |
| `persist.flipunlock.ime` | true | Input method freedom (InputMethodHook + Sogou) |

**Coupling**: `gesture.home` and `gesture.back` should be kept together (both ON or both OFF). `display.aod` depends on `display.dual`. Module logs warnings at startup if mismatched. `display.cutout`, `ui.widget`, `ime` are independent.

### Hook Architecture

```
onSystemServerStarting (system_server):
├── DisplayStateHook          → dual display + enable guard + AOD power
├── CutoutHook.hookFramework  → Display.getCutout + Parser
├── LetterboxHook             → isLetterboxedForDisplayCutout → false
├── WhitelistHook             → ContinuityPolicyService dump
├── CompatConfigHook          → continuity.policy + PROPERTY_COMPAT
├── AppBoundsHook             → fillInsetsState + LaunchActivityItem
├── SystemServicesHook        → BoundsCompatUtils + getFullScreenValue
├── InputMethodHook           → shouldShowCurrentInput + isFlipTinyScreen
├── InterceptHook             → isInterceptListUnCheckFold
└── SubScreenGestureHook      → displayId redirect (1→0) for state=6

onPackageReady:
├── DeviceIdentityHook [* excl. SystemUI, Sogou] → isFlipDevice + 6 static fields
├── ScreenTypeHook [*]          → getScreenType → 0 (EXPAND)
├── AodHook [systemui, aod]     → v2.3: screen state fix + FlipLinkageStyleController
├── CameraHook [camera]         → v2: dynamic LENS_FACING enumeration (disabled)
├── CutoutHook [systemui, aod, camera]
├── SystemUIHook [systemui]     → widget decor, notification, clock, icons, NavigationBar force
├── GestureHook [fliphome]      → disable FlipLauncher + block start pages
├── LauncherHook [miui.home]    → 3-gate NavStubView fix: fold + hideLine + defaultHome
├── LockScreenHook [systemui]   → lock screen large layout: isTinyScreen/FlipTinyScreen/Instant toggles
├── WatchOverlayHook [fliphome] → 5-layer widget defense: root getWatchOverlay()→null + 4 layers
├── SogouInputHook [sogou]      → toolbar + clipboard (DexKit)
└── ActivityLifecycleHook [*]   → layoutInDisplayCutoutMode=ALWAYS
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

- **CameraHook** — Front camera redirect on outer screen (not working — HAL reports all cameras as LENS_FACING_BACK, fallback "1"→"0" not yet verified)
- **FaceUnlock** — Face unlock on outer screen (confirmed infeasible — see below)

### Known Issues (Unfolded State)

As of v2.8, `DisplayStateHook` and `CameraHook` include a fold-state guard (`isOuterScreen()`: display height < 2000px). When the device is unfolded with the inner screen intact, these hooks are automatically disabled — the native display topology and front camera work normally. No manual configuration needed.

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
- 系统手势（返回） — 禁用 fliphome FlipLauncher，保留 GestureStubView 边缘返回
- 系统手势（Home/Recents） — 修复 miuihome NavStubView 三道门控：折叠移除、hideGestureLine、默认桌面检查
- 折叠状态下外屏 AOD 启用（v2.3 — 屏幕状态修复）
- 前置摄像头重定向到主后摄（v2 — 动态 LENS_FACING 枚举）
- 外屏双击休眠 + 三指截屏手势（displayId 修复适配 state=6）
- 功能开关 — 基于 SystemProperties，重启生效，无界面

### 功能开关

所有功能可通过 `setprop` 单独关闭。修改后重启生效。无 UI — 有意为之。

```bash
# 查看当前设置
getprop | grep persist.flipunlock

# 关闭某个功能（示例）
setprop persist.flipunlock.ui.widget false
reboot

# 重新开启
setprop persist.flipunlock.ui.widget true
reboot
```

| 属性 | 默认 | 控制 |
|------|------|------|
| `persist.flipunlock.enable` | true | **总开关** — false 则全部关闭 |
| `persist.flipunlock.display.dual` | true | 双屏显示（DisplayStateHook：强制 state=6，display enable） |
| `persist.flipunlock.display.aod` | true | 外屏 AOD |
| `persist.flipunlock.display.cutout` | true | 去除挖孔 + letterbox + appBounds + lifecycle |
| `persist.flipunlock.gesture.home` | true | 底部手势 Home/Recents（LauncherHook） |
| `persist.flipunlock.gesture.back` | true | 返回手势（GestureHook：禁用 FlipLauncher） |
| `persist.flipunlock.ui.lockscreen` | true | 锁屏大屏样式（LockScreenHook） |
| `persist.flipunlock.ui.widget` | true | 禁用外屏小部件（WatchOverlayHook） |
| `persist.flipunlock.ime` | true | 输入法自由切换（InputMethodHook + Sogou） |

**耦合**：`gesture.home` 与 `gesture.back` 建议保持同开同关。`display.aod` 依赖 `display.dual`。模块启动时若有冲突会打印 `⚠` 警告。`display.cutout`、`ui.widget`、`ime` 三者独立。

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

- **CameraHook** — 外屏前置摄像头重定向（不生效 — HAL 上报所有摄像头均为 LENS_FACING_BACK，回退方案 "1"→"0" 未验证）
- **FaceUnlock** — 外屏人脸解锁（已确认不可行 — 详见下）

### 已知问题（展开状态下）

v2.8 起 `DisplayStateHook` 和 `CameraHook` 加入了折叠态守卫（`isOuterScreen()`: 屏幕高度 < 2000px）。展开时自动放行——原生显示拓扑和前置摄像头正常工作。无需手动配置。

### License

AGPL-3.0
