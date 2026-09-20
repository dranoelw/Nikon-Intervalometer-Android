# Nikon Intervalometer (D3400 test build)

Minimal Android USB-host/PTP intervalometer.

## Features
- Detects USB still-image/PTP camera
- Requests Android USB permission
- Opens PTP session
- Sends PTP InitiateCapture (0x100E)
- Configurable interval, shot count, and start delay
- Start/Stop controls and shot progress
- Keeps screen awake while app is open

## Hardware
- Android phone/tablet with USB host (OTG) support
- USB OTG adapter/cable
- Nikon D3400 USB cable/connection

## Build
Requires Android SDK 35 and JDK 17+.

```bash
gradle assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Notes
This is intentionally a minimal PTP implementation. Camera behavior can depend on shooting mode, focus state, storage state, and firmware. If the D3400 returns a PTP error code, the UI shows that response code so compatibility can be adjusted.
