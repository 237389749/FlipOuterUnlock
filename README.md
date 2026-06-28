# FlipOuterUnlock

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — unlock the outer display.

## Features

### Display & Fullscreen
- **Remove outer screen display cutout** — clears the camera hole-punch via `Display.getCutout()` zero-cutout injection and `CutoutSpecification.Parser` field nullification
- **Force fullscreen for all apps** — sets `layoutInDisplayCutoutMode=ALWAYS` on every Activity, forces `BoundsCompatUtils.getFlipCompatMode()` to 0
- **Spoof device identity** — hooks `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `miui.os.Build`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, `MiuiConfigs` — covers all 7 independent detection paths
- **Whitelist all apps** — uses `ContinuityPolicyService` dump injection to allow all apps on the external screen
- **Compat config injection** — `ApplicationCompatManager` → `miui.continuity.policy=5`, `PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN=1`
- **Flip continuity** — `isFlipContinuityEnabledFromSetting` → always true
- **App bounds fixes** — `fillInsetsState` (remove cutout insets), `LaunchActivityItem` (cold start bounds), `scheduleConfigurationChanged` + `scheduleClientTransactionItem` (config change bounds)

### IME & Input
- **Enable IME in landscape** — hooks `shouldShowCurrentInput()` → true, suppresses rotation toast
- **Remove app launch restrictions** — hooks `InterceptActivityController.isInterceptListUnCheckFold()` → false

### Widget Overlay
- **Disable WatchOverlay widget** — 4-layer defense: controller (CheckAppConfigRunnable), view (WatchOverlayGroupView), window (WatchOverlayWindow), and WindowManager.addView interception

### Experimental (commented out for stability testing)
- `ScreenTypeHook` — `Configuration.getScreenType()` → 0
- `LetterboxHook` — `WindowState.isLetterboxedForDisplayCutout()` → false
- `SubScreenGestureHook` — `MiuiSubScreenMultiFingerGestureManager` init for Mix Flip
- `SystemUIHook` (expanded) — notification menu, clock hiding, status bar icon expansion
- `GestureHook` — FlipLauncher disable + gesture engine keep-alive + no-start-page

## Hook Architecture

```
onSystemServerStarting (system_server):
├── CutoutHook.hookFramework    → Display.getCutout + CutoutSpecification.Parser
├── WhitelistHook               → ContinuityPolicyService dump injection
├── CompatConfigHook            → ApplicationCompatManager + flip continuity
├── AppBoundsHook               → fillInsetsState + LaunchActivityItem + config changes
├── SystemServicesHook          → BoundsCompatUtils + WindowManagerServiceImpl
├── InputMethodHook             → shouldShowCurrentInput + makeRotateToast
└── InterceptHook               → isInterceptListUnCheckFold + isInterceptListForProperty

onPackageReady:
├── DeviceIdentityHook [*]      → 7 device identity methods → false
├── CutoutHook [systemui,aod,camera] → Display.getCutout per-process
├── ActivityLifecycleHook [*]   → layoutInDisplayCutoutMode=ALWAYS on all Activities
└── WatchOverlayHook [fliphome] → 4-layer widget overlay defense
```

## Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

## Build

```bash
./gradlew :app:assembleDebug
```

### Release build (signed)

Set signing properties in `gradle.properties`:
```properties
androidStoreFile=key.jks
androidStorePassword=...
androidKeyAlias=...
androidKeyPassword=...
```

## Credits

Ported from [FlipOutScreenUnlock](https://github.com/237389749/FlipOutScreenUnlock) (Xposed/Java) to LSPosed/Kotlin.

Reverse engineering references in `refMD/cleaned/` based on decompiled MIUI framework, services, and fliphome APKs.

## License

AGPL-3.0
