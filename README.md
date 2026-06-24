# FlipOuterUnlock

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — force fullscreen on the outer display.

## Features

- **Remove outer screen display cutout** — clears the camera hole-punch area so the entire 1208×1392 outer screen is usable
- **Force fullscreen for all apps** — sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` on every Activity
- **Remove app launch restrictions** — any app can start on the outer display
- **Enable IME in landscape** — keyboard works on the outer screen without rotation restrictions
- **Disable WatchOverlay widget** — removes the watch-face overlay that blocks touch on the cover screen

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
Then:
```bash
./gradlew :app:assembleRelease
```

## Credits

Ported from [FlipOutScreenUnlock](https://github.com/237389749/FlipOutScreenUnlock) (Xposed/Java) to LSPosed/Kotlin.

## License

AGPL-3.0
