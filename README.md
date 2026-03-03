# BT Mic Switch

Force your connected Bluetooth neckband/headset to act as the primary microphone for **all** audio recording on your Android device — not just calls.

## What it does

When active, BT Mic Switch opens a Bluetooth SCO channel via Android's `AudioManager` and sets `MODE_IN_COMMUNICATION`, which causes the OS to route microphone input from **all apps** (camera, WhatsApp, voice recorder, Instagram, etc.) through your BT headset's mic, while keeping normal audio playback working over BT simultaneously.

## Requirements

- Android 10 (API 29) or higher  
- Samsung Galaxy A70s (tested target) or any Android device  
- A Bluetooth headset/neckband that supports **HFP or HSP profiles** (most do)

## Features

- ✅ Large toggle button — one tap to activate/deactivate  
- ✅ Live status: "BT Mic Active" / "Using Internal Mic"  
- ✅ Shows connected Bluetooth device name  
- ✅ Persistent foreground notification with quick "Turn Off" action  
- ✅ WakeLock keeps SCO alive in background  
- ✅ Auto-retries SCO connection up to 5 times if it drops  
- ✅ Auto-reverts + notifies if BT device disconnects  
- ✅ No root required — standard Android APIs only  
- ✅ Kotlin + Material Design

## Build Instructions

### Prerequisites
- Android Studio Hedgehog or newer  
- JDK 11+  
- Android SDK API 33 installed

### Steps
1. Open Android Studio  
2. **File → Open** → select the `BTMicSwitch` folder  
3. Let Gradle sync complete  
4. Connect your Samsung A70s (enable Developer Options + USB Debugging)  
5. Click **Run ▶**

### Or build APK manually:
```bash
cd BTMicSwitch
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

Then install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions Required

| Permission | Reason |
|---|---|
| `RECORD_AUDIO` | Access audio subsystem for mic routing |
| `MODIFY_AUDIO_SETTINGS` | Change audio mode and SCO state |
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | Query/communicate with BT device |
| `FOREGROUND_SERVICE` | Keep service alive while in background |
| `WAKE_LOCK` | Prevent CPU sleep during active SCO |

## How it works (technical)

1. **User taps ON** → `MainActivity` starts `BluetoothScoService` as a foreground service  
2. **Service calls** `audioManager.mode = MODE_IN_COMMUNICATION` then `startBluetoothSco()`  
3. **`ACTION_SCO_AUDIO_STATE_UPDATED` broadcast** fires when SCO connects → UI updates to "BT Mic Active"  
4. **Any app** that opens `AudioRecord` or `MediaRecorder` now gets audio from the BT mic  
5. **If BT device disconnects** → `BluetoothDevice.ACTION_ACL_DISCONNECTED` triggers → service stops, audio reverts to internal mic, user gets Toast notification  
6. **SCO drops unexpectedly** → service retries up to 5 times with 2-second delay between attempts  
7. **User taps OFF** → service sends `ACTION_STOP`, calls `stopBluetoothSco()`, resets `MODE_NORMAL`

## Known Limitations

- SCO audio is **narrowband (8kHz/16kHz)** — this is a Bluetooth HFP limitation, not an app limitation. Some newer headsets support wideband (HD Voice).  
- Some apps that use `AudioFocus` exclusively or have their own audio routing may override the SCO channel.  
- Samsung's audio HAL on One UI may occasionally reset audio mode — if routing drops, toggle OFF then ON again.  
- On Android 12+ (API 31+), `BLUETOOTH_CONNECT` permission requires explicit user grant.

## Project Structure

```
BTMicSwitch/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/btmicswitch/
│   │   ├── MainActivity.kt          # UI + permission handling
│   │   ├── BluetoothScoService.kt   # Core SCO management foreground service
│   │   └── BootReceiver.kt          # Boot broadcast receiver (stub)
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/{colors,strings,themes}.xml
│       ├── color/{toggle_thumb,toggle_track}.xml
│       └── drawable/                # Vector icons
└── build.gradle
```
