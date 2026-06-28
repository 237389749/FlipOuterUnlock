# FlipOuterUnlock / MIX Flip 外屏解锁模块

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
- Spoof device identity — hooks 7 detection paths: `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `miui.os.Build`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, `MiuiConfigs`
- Spoof screen type — hooks MIUI's `Configuration.getScreenType()` to return 0 (EXPAND)
- Fix camera preview rotation — hooks `getAdjustedRotation()` to maintain 180° compensation

**App Management**
- Whitelist all apps for continuity — uses `ContinuityPolicyService` dump injection
- Compat config injection — `ApplicationCompatManager` → `miui.continuity.policy=5`
- Remove app launch restrictions — `InterceptActivityController.isInterceptListUnCheckFold()` → false

**IME & Input**
- Enable IME in landscape — hooks `shouldShowCurrentInput()` → true
- Suppress rotation toast
- Lock Sogou as preferred IME when folded
- Sogou toolbar & clipboard fix — restores full keyboard layout on outer screen (uses DexKit)
- **Known issue**: Scan preview orientation varies by app. Some apps require holding the phone with the camera side down BEFORE opening the scan; others use their own camera logic and work regardless.

**SystemUI**
- Widget overlay disabled — 4-layer defense in fliphome process
- SystemUI-side widget suppression — hides decor window
- Notification menu fix — restores long-press menu via `isTinyScreen` scope faking
- Status bar clock hidden on outer screen
- Status bar icon expansion — shows up to 8 notification icons

### Hook Architecture

```
onSystemServerStarting (system_server):
├── CutoutHook.hookFramework
├── LetterboxHook
├── WhitelistHook
├── CompatConfigHook
├── AppBoundsHook
├── SystemServicesHook
├── InputMethodHook
└── InterceptHook

onPackageReady:
├── DeviceIdentityHook [* except SystemUI]
├── CutoutHook [systemui, aod, camera]
├── SystemUIHook [systemui]
├── SogouInputHook [sogou]
├── ActivityLifecycleHook [*]
└── WatchOverlayHook [fliphome]
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

For CI, add GitHub Secrets: `KEY_STORE` (base64), `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — reference for LSPosed architecture, SogouHook, DexKit usage, SystemUI hooks, and hook utilities
- Reverse engineering references in `refMD/cleaned/` (decompiled MIUI framework, services, fliphome APKs)

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
- 伪装设备类型：hook 7 条检测路径（`MiuiMultiDisplayTypeInfo`、`miuix.os.Build` 等）
- 伪装屏幕类型：`Configuration.getScreenType()` 返回 0
- 修复相机预览旋转：`getAdjustedRotation()` 保持 180° 补偿

**应用管理**
- 所有应用白名单注入
- 兼容配置注入：`miui.continuity.policy=5`
- 移除应用启动拦截

**输入法**
- 横屏键盘启用 + 禁旋转提示
- 折叠时锁 Sogou 为默认输入法
- Sogou 工具栏+剪贴板修复（DexKit）
- **已知问题**：扫一扫预览方向因应用而异。部分应用需在**点击扫一扫前**以靠近摄像头一侧为底才能正常显示；部分应用走自带逻辑，无需调整即可正常

**SystemUI**
- Widget 覆盖层 4 层禁用
- SystemUI 侧 widget 隐藏
- 通知菜单修复
- 外屏状态栏时钟隐藏
- 通知图标扩展到 8 个

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

CI: GitHub Secrets → `KEY_STORE`(base64), `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、SogouHook、DexKit、SystemUI hook 及工具类参考
- `refMD/cleaned/` — MIUI 框架及 fliphome 反编译参考文档

### License

AGPL-3.0
