# FlipOuterUnlock

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — unlock the outer display.

## Features

### Display & Fullscreen
- **Remove outer screen display cutout** — clears the camera hole-punch via `Display.getCutout()` zero-cutout injection and `CutoutSpecification.Parser` field nullification
- **Prevent cutout letterboxing** — hooks `WindowState.isLetterboxedForDisplayCutout()` in system_server
- **Force fullscreen for all apps** — sets `layoutInDisplayCutoutMode=ALWAYS` on every Activity, forces `BoundsCompatUtils.getFlipCompatMode()` to 0
- **Spoof device identity** — hooks `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `MiuiConfigs.isTinyScreen/isFlipTinyScreen`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, and more — covers all 7 independent detection paths
- **Spoof screen type** — hooks MIUI's `Configuration.getScreenType()` to always return 0 (EXPAND) instead of 1 (FOLD)
- **Whitelist all apps for continuity** — uses `ContinuityPolicyService` to allow all apps on the external screen

### Gestures
- **Preserve external screen gestures** — keeps `TouchInteractionService` and `BaseGestureImpl` alive when FlipLauncher is disabled
- **Enable sub-screen gestures** — force-initializes `MiuiSubScreenMultiFingerGestureManager` on Mix Flip (normally only for independent rear devices), unlocking system-level multi-finger gestures on the external display
- **Force gesture input in folded state** — bypasses `onDisplayFoldChanged` event dependency after `BaseGestureImpl.init()`

### IME & Input
- **Enable IME in landscape** — hooks `shouldShowCurrentInput()` → true, suppresses rotation toast
- **Remove app launch restrictions** — hooks `InterceptActivityController.isInterceptListUnCheckFold()` → false

### Widget Overlay
- **Disable WatchOverlay widget** — 4-layer defense: controller (CheckAppConfigRunnable), view (WatchOverlayGroupView hooks), window (WatchOverlayWindow), and WindowManager.addView interception
- **SystemUI-side widget suppression** — hooks `DecorWindowManagerImpl.shouldHideDecorWindow()` → true

## Hook Architecture

```
onSystemServerStarting (system_server):
├── CutoutHook.hookFramework    → Display.getCutout + CutoutSpecification.Parser
├── LetterboxHook               → isLetterboxedForDisplayCutout → false
├── WhitelistHook               → ContinuityPolicyService dump injection
├── SubScreenGestureHook        → MiuiSubScreenMultiFingerGestureManager init
├── SystemServicesHook          → BoundsCompatUtils + WindowManagerServiceImpl
├── InputMethodHook             → shouldShowCurrentInput + makeRotateToast
└── InterceptHook               → isInterceptListUnCheckFold + isInterceptListForProperty

onPackageReady:
├── ScreenTypeHook [*]          → Configuration.getScreenType → 0
├── DeviceIdentityHook [*]      → 7 device identity methods → false
├── CutoutHook [systemui,aod,camera] → Display.getCutout per-process
├── SystemUIHook [systemui]     → DecorWindowManagerImpl.shouldHideDecorWindow
├── GestureHook [fliphome]      → disable FlipLauncher + keep gesture engine alive
├── ActivityLifecycleHook [*]   → layoutInDisplayCutoutMode=ALWAYS on all Activities
└── WatchOverlayHook [fliphome] → 4-layer widget overlay defense
```

## Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI
- Recommended scope: system, com.android.systemui, com.miui.aod, com.android.camera, com.miui.fliphome, and all apps

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
Then:
```bash
./gradlew :app:assembleRelease
```

## Credits

Ported from [FlipOutScreenUnlock](https://github.com/237389749/FlipOutScreenUnlock) (Xposed/Java) to LSPosed/Kotlin.

Reverse engineering references in `refMD/cleaned/` based on decompiled MIUI framework, services, and fliphome APKs.

## License

AGPL-3.0
