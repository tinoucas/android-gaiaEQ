# GAIA EQ

Android app (and Python tools) to control the **built-in user EQ** of Bluetooth
receivers built on Qualcomm QCC51xx chips running ADK firmware, over Qualcomm's
GAIA v3 protocol. It also fits [AutoEq](https://github.com/jaakkopasanen/AutoEq)
headphone corrections to the device's EQ bands.

Tested on QCC5181 IEM neckbands:

| Device | Result |
|---|---|
| BGVP M4 | ✅ read + write, audible |
| BT-5181Pro (generic, two builds) | ✅ read + write, audible |

Any device that answers GAIA v3 over RFCOMM and lists the *Music Processing*
feature (0x05) should work: the app reads the band layout from the device.

## What the firmware offers

On the tested devices the user EQ is a **5-band graphic EQ**: fixed peaking
bands at 120 / 500 / 1500 / 5000 / 12000 Hz, Q ≈ 0.70. Only the **gains** are
writable over GAIA; frequency and Q are fixed in the firmware config (a
full-band write is rejected with *invalid parameter*). Gains and the selected
preset survive power cycles.

## Android app (`android/`)

- Lists paired devices, opens the GAIA RFCOMM channel (SPP UUID), checks for
  the EQ feature.
- Five sliders (−12…+6 dB), Flat / Custom toggle, response graph.
- AutoEq search (index cached for a week); the chosen correction is fitted to
  the device's bands (weighted least squares, free level offset) and previewed
  as target vs fit before applying. Optional *cuts only* mode.
- Named presets saved on the phone.

Requires Android 12+ (minSdk 31).

**Download:** signed APKs are attached to
[Releases](../../releases). Install it on the phone (allow installing from your
browser or file manager when Android asks).

**Releasing:** push a tag `vX.Y.Z`. The *Release APK* workflow tests,
builds and signs the APK on GitHub and publishes it as a release. It needs the
repo secrets `KEYSTORE_BASE64` (base64 of the release keystore, alias `gaiaeq`)
and `KEYSTORE_PASSWORD`.

Build locally:

```sh
cd android
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point `sdk.dir` at an Android SDK with platform 35.

## Python tools (Linux / BlueZ)

Run with `pipx run <script>`; dependencies are declared inline (PEP 723).

| Script | Purpose |
|---|---|
| `gatt_probe.py` | Read-only BLE GATT enumeration; flags Qualcomm GAIA UUIDs |
| `gaia_query.py` | GAIA v3 client (BLE or `--rfcomm`); dumps features + EQ, gated set commands |
| `m4_autoeq.py` | Fit an AutoEq preset to the 5 bands and write it (`--search`, `--gains`, `--flat`, `--dry-run`) |

The device must be paired with the PC over classic Bluetooth. Use `--address`
when several devices share a name.

## Protocol notes

GAIA v3 over RFCOMM, frame `FF | ver | flags | len | vendor(2) | cmd(2) | payload`,
vendor `0x001D`, `cmd = feature<<9 | type<<7 | id` (type 0 cmd, 1 notif,
2 response, 3 error).

Music Processing (feature 0x05) commands, verified on hardware:

| id | command | payload → reply |
|---|---|---|
| 0x00 | get EQ state | → `[state]` (0 while audio idle, 1 while streaming) |
| 0x01 | get presets | → `[count, ids…]` (0 = flat, 63 = user) |
| 0x02 | get selected set | → `[id]` |
| 0x03 | select set | `[id]` (only sticks while audio is streaming) |
| 0x04 | get user band count | → `[n]` |
| 0x05 | get user band config | `[start, end]` → `[start, end, (freq u16, Q u16/4096, type u8, gain i16/60)…]` |
| 0x06 | set user band gains | `[start, end, gain i16 (dB×60)…]` |

Firmware quirks:

- Preset selection is ignored while no audio is streaming.
- Switching from another preset back to the user set (63) does not reload the
  stored gains into the DSP (the device reports set 63 and the stored gains,
  but audio stays flat). The app works around this by re-writing the gains
  after selecting 63, which restores the EQ (confirmed by ear on the M4).
- BLE GAIA requires an encrypted link and the tested devices refused LE
  pairing from Linux; RFCOMM over the existing classic bond works.
- The tested units share serial `ABCDEF0123456789`, firmware
  `1.0.0 "(unreleased)"` and `00:02:5B:00:FF:xx` addresses, i.e. close to
  Qualcomm reference firmware.

## License

[MIT](LICENSE). AutoEq profiles are downloaded at runtime from the
[AutoEq](https://github.com/jaakkopasanen/AutoEq) repository and are not
included here.

## Disclaimer

Unofficial; not affiliated with Qualcomm, BGVP or AutoEq. Only gain writes
and preset selection are sent; use at your own risk.
