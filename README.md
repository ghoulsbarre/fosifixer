# FosiFix

A minimal Android app for GrapheneOS (or any Android 8+) that fixes the noise gate cutout issue on the **Fosi Audio MC331** amplifier.

---

## The Problem

The MC331 uses a MVSilicon BP1048B2 DSP chip. Its default firmware settings for the Music Noise Suppressor and Silence Detector are aggressive enough to cut out low-volume passages — most noticeably with classical music, acoustic recordings, and quiet intros. The amp simply silences anything below its threshold, treating it as background noise.

Fosi's official tool, **ACP Workbench** (Windows only), lets you adjust these DSP registers over USB. The problem is that the settings live in volatile memory — every time the amp reboots, the defaults are restored. There is no way to save custom settings permanently to the device.

---

## The Solution

The BP1048B2 exposes a USB HID control interface regardless of which audio input is selected. By sending a specific 65-byte HID payload to the DSP over USB, we can overwrite the Noise Suppressor register (`0x88`) with friendlier settings:

| Parameter | Value |
|-----------|-------|
| Ratio | 100 |
| Threshold | −90 dB |
| Attack | 0 ms |
| Release | 10 ms |

These values effectively make the noise suppressor transparent — it will no longer cut out quiet musical passages.

This approach was first documented by a user on the Fosi Audio community forums, who reverse-engineered the HID payload using USBPcap on Windows. FosiFix packages that same payload into a simple Android app so you don't need a Windows machine, ACP Workbench, or any desktop software at all.

---

## How It Works

1. The MC331, when connected via USB-C and set to any non-Bluetooth input, presents itself as a USB HID device (`VID 0x8888`, `PID 0x1717`)
2. FosiFix detects this device, waits 3 seconds for the MCU to complete its boot routine, then writes the payload to all available HID OUT endpoints
3. The DSP accepts the write on the control endpoint and applies the new register values immediately
4. The settings persist until the amp loses power

> **Note on Bluetooth mode:** When the amp is set to Bluetooth input, it exposes no USB HID interface. The workflow is therefore: power on the amp → plug in USB-C → open FosiFix → wait for ✅ → unplug → switch to Bluetooth. The DSP settings survive the input switch.

---

## Requirements

- Android 8.0+ (minSdk 26)
- GrapheneOS recommended, but any Android device with USB host support works
- A USB-C to USB-C cable (or USB-C to USB-A with an OTG adapter depending on your phone)
- Fosi Audio MC331 amplifier

---

## Installation

### Option A — Obtainium (recommended)

[Obtainium](https://github.com/ImranR98/Obtainium) lets you install and update FosiFix directly from this GitHub repo.

1. Install Obtainium on your phone
2. Add this repo URL: `https://github.com/YOUR_USERNAME/fosifixer`
3. Obtainium will find the latest release APK and install it
4. Future updates will be flagged automatically

### Option B — Direct APK download

1. Go to the [Releases](../../releases) page
2. Download `FosiFix.apk`
3. Enable **Install unknown apps** for your file manager in Settings
4. Tap the APK to install

---

## Usage

1. Power on the MC331
2. Plug it into your phone via USB-C
3. Open **Fosi Fix**
4. When prompted, grant USB access (tick *Always allow* so it only asks once)
5. Watch the countdown — the app waits 3 seconds for the MCU to finish booting
6. Status shows 🟢 **Active** — payload sent
7. Close the app and unplug the cable
8. Switch the amp to Bluetooth (or whichever input you use) and enjoy

The whole process takes about 10 seconds.

---

## Status Indicators

| Status | Meaning |
|--------|---------|
| 🔴 Waiting | Amp not detected on USB |
| 🟡 Connecting | Amp found, counting down MCU boot (3s) |
| 🟢 Active | Payload sent successfully |

---

## Building from Source

The project uses a standard Gradle setup. You need JDK 17 and the Android SDK.

```bash
git clone https://github.com/YOUR_USERNAME/fosifixer.git
cd fosifixer
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit **Build → Build APK**.

Tagged releases are built automatically via GitHub Actions and published to the Releases page.

---

## Technical Details

**Device identifiers**
- Vendor ID: `0x8888` (34952 decimal)
- Product ID: `0x1717` (5911 decimal) — USB/ACP control mode

**Payload**
```
00 A5 5A 88 0B FF 00 00 70 E5 03 00 05 00 64 00 16 [+ 48 zero bytes]
```
- Byte 0: HID Report ID (`0x00`)
- Bytes 1–2: Packet header (`0xA5 0x5A`)
- Byte 3: Register address (`0x88` = Music Noise Suppressor)
- Remaining bytes: Register payload encoding the settings above

The app attempts to write to all HID OUT endpoints. Audio endpoints will reject the write — this is expected and silently ignored. The control transfer endpoint accepts it.

**What this does not change**
- The Silence Detector register (`0x89`) is not modified — its default Amplitude setting of 0 is already correct and requires no adjustment
- No other DSP registers are touched

---

## Credits

- Payload reverse-engineered by a member of the Fosi Audio community forums

---

## Disclaimer

This app communicates with the DSP over the same USB HID interface used by Fosi's official ACP Workbench software. It writes only to the documented Noise Suppressor register. Use at your own risk.
