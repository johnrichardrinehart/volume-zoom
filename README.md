# Volume Zoom

Lets you turn media quieter than Android's lowest volume step, using only the hardware
volume keys.

## Behaviour

1. Turn media down to its lowest audible step (index 1) the usual way.
2. **Hold volume down** for 400 ms. The stream stays at index 1 and fine step 1 of
   software attenuation kicks in. Keep holding and it goes one step deeper every
   250 ms (10 steps, by default 3 dB each, down to −30 dB). Holding never mutes.
3. In fine mode:
   - volume down: one step quieter, repeating while held. A fresh press on step 10
     mutes and leaves fine mode.
   - volume up: one step louder, repeating while held. Reaching step 0 leaves fine mode
     and stops at plain index 1 even if you keep holding. Further presses work as usual.
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

## Build and install

```sh
nix develop -c gradle testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Android 13+, sideloaded apps cannot enable accessibility services until you open
**App info → ⋮ → Allow restricted settings**. After that, enable *Volume Zoom* under
**Settings → Accessibility**. Over adb you can do the same with:

```sh
adb shell settings put secure enabled_accessibility_services \
  dev.johnrinehart.volumezoom/.ZoomService
adb shell settings put secure accessibility_enabled 1
```

(The first command replaces any other enabled services. Append with `:` if you use any.)

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

| fine step | expected | measured below floor |
|---|---|---|
| 0 (floor, idx 1 = −51.2 dBFS at the mic) | 0 | 0 |
| 1 | −3 | −2.5 |
| 2 | −6 | −5.3 |
| 3 | −9 | −8.3 |
| 4 | −12 | −11.2 |
| 5 | −15 | −14.6 |
| 6 | −18 | −17.9 |
| 7 | −21 | −20.5 |
| 8 | −24 | −23.8 |
| 9 | −27 | −25.9 |
| 10 | −30 | −30.5 |
| mute (down on step 10) | — | at the noise floor |

For comparison, index 2 is only 3.3 dB louder than index 1 on this phone.
