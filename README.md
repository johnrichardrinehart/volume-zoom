# Volume Zoom

Lets you turn media quieter than Android's lowest volume step, using only the hardware
volume keys.

## Behaviour

1. Turn media down to its lowest audible step (index 1) the usual way.
2. **Hold volume down** for 400 ms to zoom in. You land on fine step 0, which is plain
   index-1 volume (0 dB). The hold only switches modes and does not step any further.
3. In fine mode (10 steps below 0 dB, by default 3 dB each, down to −30 dB):
   - volume down: one step quieter, repeating while held. Holding stops at step 10.
     A fresh press on step 10 mutes and leaves fine mode.
   - volume up: one step louder, repeating while held, stopping at 0 dB. A fresh up
     press at 0 dB zooms out and raises the system volume as usual.
   - any other change to the media volume (system slider, switching to headphones or
     Bluetooth, screen-off key presses) leaves fine mode.
4. A quick press at index 1 still mutes, on release.

This works with nothing playing too, because on Pixel/AOSP the idle volume keys adjust
media. On phones where idle keys change the ringer, turn off "Volume keys control media
when nothing is playing" in the app, and it will then only act while media plays.

A small indicator at the right edge shows the current fine step. You can turn it off.

## How it works

- `ZoomService` is an accessibility service that filters key events
  (`flagRequestFilterKeyEvents`). It only takes keys when the audio mode is normal and
  media is playing, or while fine mode is active. It never handles calls or the ringer.
- `Attenuator` puts a `DynamicsProcessing` effect on the global output mix (session 0)
  and sets its input gain, which is a flat dB scaler. The effect exists only while fine
  mode is on. Outside fine mode the audio path is left alone.
- `ZoomController` is the state machine. It has no Android dependencies and is unit
  tested.

## Limitations

- Accessibility key filtering only works with the screen on. With the screen off, volume
  keys go to the system, change the media index, and so leave fine mode.
- Global (session 0) effects are deprecated. They work on Pixels, but some OEMs or
  offloaded or hardware-tunneled playback can bypass them.
- Another app holding a higher-priority global effect can take control. The indicator
  then shows `lost`.

## Install

Download `volume-zoom-vX.Y.Z.apk` from
[Releases](https://github.com/johnrichardrinehart/volume-zoom/releases) and check it
against the `.sha256` file. Every release is signed with the same key:

```
SHA-256 8A:DD:AC:26:87:05:59:60:6D:8E:F9:ED:D5:8D:86:89:DF:18:E1:AE:69:B3:48:BF:77:95:C3:5E:76:B2:2C:8C
```

Then, in **Settings → Accessibility → Volume Zoom**, turn on **Use Volume Zoom**. Leave
**Volume Zoom shortcut** off: it only adds a floating accessibility button, and the volume
keys work without it.

If you installed the APK from a file, Android 13+ answers with "App was denied access".
After that first refusal, **App info → ⋮ → Allow restricted settings** becomes available.
Allow it, then turn the service on again. Installs from an app store such as F-Droid are
not restricted.

## Build

The build uses Nix only: aapt2, kotlinc and d8 run directly, with no Gradle.

```sh
nix build            # result/volume-zoom-unsigned.apk
nix flake check      # APK, state-machine tests, formatting
nix run .#install    # sign with the release key, adb install, enable the service
```

`nix run .#sign -- IN.apk OUT.apk` signs with `VOLUME_ZOOM_KEYSTORE` and
`VOLUME_ZOOM_KEYSTORE_PASSWORD`, or with `~/.local/share/volume-zoom/release.{p12,pass}`
if those aren't set. The state machine (`core/`) is plain Kotlin with no Android
dependencies. Its tests run on the JVM.

## Releasing

Bump `version` and `versionCode` in `nix/packages/volume-zoom.nix`, then push a tag
`v<version>`. The release workflow runs the checks, checks the tag against the version,
signs the APK with the keystore from the `VOLUME_ZOOM_KEYSTORE_BASE64` and
`VOLUME_ZOOM_KEYSTORE_PASSWORD` secrets, and publishes the APK with its SHA-256.

## Testing on a device

`adb shell input keyevent` events skip accessibility key filters, so they can't exercise
the zoom. The filters also get no auto-repeat, only the down and up edges, so the
controller times holds and repeats itself. Use a virtual uinput keyboard, which goes through the same path as the
hardware keys:

```sh
python3 tools/uinput-keys.py down:600 | adb shell uinput -   # hold 600 ms
python3 tools/uinput-keys.py up | adb shell uinput -
adb logcat -s VolumeZoom
```

`tools/measure-tone.py` records the laptop's raw mic (not the rnnoise source) and
reports the level of a 1 kHz test tone, taking the median over 250 ms windows.

### Measured (Pixel 11 Pro XL, Android 17, speaker, media index 1/25)

1 kHz tone at −4 dBFS, phone 2–5 cm from the laptop mic. "Below floor" is
noise-corrected; steps 9–10 had only 3–8 dB SNR.

| fine step                                | expected | measured below floor |
| ---------------------------------------- | -------- | -------------------- |
| 0 (floor, idx 1 = −51.2 dBFS at the mic) | 0        | 0                    |
| 1                                        | −3       | −2.5                 |
| 2                                        | −6       | −5.3                 |
| 3                                        | −9       | −8.3                 |
| 4                                        | −12      | −11.2                |
| 5                                        | −15      | −14.6                |
| 6                                        | −18      | −17.9                |
| 7                                        | −21      | −20.5                |
| 8                                        | −24      | −23.8                |
| 9                                        | −27      | −25.9                |
| 10                                       | −30      | −30.5                |
| mute (down on step 10)                   | —        | at the noise floor   |

For comparison, index 2 is only 3.3 dB louder than index 1 on this phone.

## License

[0BSD](LICENSE)
