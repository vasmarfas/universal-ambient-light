<img width="1280" height="720" alt="Universal Ambient Light" src="docs/banner.png" />

# Universal Ambient Light

Ambient screen lighting for Android. The app captures the screen (or films the TV with the
device camera), extracts the edge colors and streams them to an LED strip, a controller or
smart lights. Without capture it can also run light effects. Runs on Android 8.0 and newer,
on both phones and Android TV.

[Читать на русском](README_RU.md) · [Support the project](SUPPORT.md) ·
[Third-party licenses](THIRD_PARTY_LICENSES.md)

## Features

- **17 kinds of outputs**: WLED, DDP, E1.31 (sACN), Art-Net, tpm2.net, UDP raw, Open Pixel
  Control, Adalight and other USB boards, Philips Hue, Nanoleaf, WiZ, Yeelight, LIFX, Govee,
  Zigbee2MQTT, Home Assistant and a Hyperion / HyperHDR server.
- **Device search**: finds WLED, Hyperion, HyperHDR, Hue bridges, Nanoleaf, Home Assistant,
  WiZ, Yeelight, LIFX, Govee and Art-Net nodes on the local network in a few seconds.
- **Phone and Android TV**: separate layouts for touchscreens and D-pad navigation.
- **Camera capture**: films the TV with the device camera and corrects perspective by four
  corners, for TVs where screen capture is unavailable.
- **Light effects without capture**: solid color, gradient, movie white, rainbow, fire,
  candle, aurora and more.
- **Lighting delay per app**, with auto-tuning: a phone camera films the TV and the glow and
  measures how far the strip is ahead of the picture on the movie that is playing.
- **Tunable pipeline**: capture quality, frame rate, color smoothing and latency.
- **Auto-start** after boot, TV sleep and app updates, **auto-reconnect** after a connection drop.
- **Phone remote**: pair with the TV by QR code, then start, stop and change every lighting
  setting of the TV from the phone. The phone also works as a TV remote with a touchpad
  mouse and a keyboard.
- **Sleep timer** that turns the lighting off after 15 minutes to 2 hours.
- **Average color mode**: sends one dominant color instead of a full strip, for weak devices.
- **Quick Settings tile** for switching the light on and off.

## Supported Controllers

| Group | Output | Notes |
|---|---|---|
| Strip over the network | WLED | DDP or UDP raw, color order, RGBW, brightness limit |
| | DDP | WLED, ESPixelStick, FPP and other DDP receivers |
| | E1.31 (sACN) | start universe, LEDs per universe, unicast or multicast |
| | Art-Net | ArtDmx, start universe, LEDs per universe |
| | tpm2.net | packets of up to 490 LEDs, port 65506 |
| | UDP raw | bare RGB in one datagram, up to 490 LEDs |
| | Open Pixel Control | FadeCandy / fcserver over TCP |
| Strip over USB | Adalight and USB kits | Ada, LightBerry APA102, AWA (HyperSerial), tpm2, Skydimo, SEDU, KarateLight, AtmoLight, SP616E |
| Smart lights | Philips Hue | through the bridge, paired with its button; an entertainment area gets a stream up to 25 times per second |
| | Nanoleaf | Light Panels, Canvas, Shapes, Elements, Lines |
| | WiZ, Yeelight, LIFX, Govee | local network protocols, no cloud |
| | Zigbee2MQTT | Zigbee lights through an MQTT broker |
| | Home Assistant | any light entity, as the main output or next to it |
| Server | Hyperion / HyperHDR | FlatBuffers, message priority |

Lights and panels get the color of a screen zone (left, top, corners, the whole screen and so
on) and follow the picture at the rate they can take, not at the frame rate of a strip.

## Requirements

- Android 8.0 (API 26) or higher.
- Screen Capture permission (MediaProjection).
- Local network access (for network controllers and lights) or USB Host support (for Adalight
  and other USB boards).

## Installation

### From GitHub Releases
The latest versions (**TV** and **Mobile**) can be downloaded from the [Releases Page](https://github.com/vasmarfas/universal-ambient-light/releases).

### [Google Play](https://play.google.com/store/apps/details?id=com.vasmarfas.UniversalAmbientLight)

### [RuStore](https://www.rustore.ru/catalog/app/com.vasmarfas.UniversalAmbientLight)

### Experimental: External USB Camera Support
An experimental build with **external USB camera (UVC) support** is available for advanced setups — for devices where screen capture is limited or not optimal. This version uses userspace drivers and is distributed outside Google Play.  
**Early access for supporters:** [Boosty — Experimental Build: External USB Camera Support](https://boosty.to/vasmarfas/posts/ba545975-558f-496f-bb3f-d181349f447c)

## Configuration

### 1. Connection
1. Launch the app and open **Settings**, or press **Set up the controller** on the home screen.
2. The controller screen lists what it found on the network. Pick a device, or pick its type
   in the list below and enter the address by hand. **Check every address** scans the whole
   subnet for WLED and Hyperion that do not announce themselves over mDNS; it takes a minute
   or two.
3. A Hue bridge and Nanoleaf panels are paired with a button on the device. Lights (Hue, WiZ,
   Yeelight, LIFX, Govee, Zigbee2MQTT, Home Assistant) are then assigned to screen zones; **Blink**
   next to a light shows which one it is. A Hue bridge with an entertainment area can work through
   the area instead, then the zones are not needed.
4. For a WLED picked from the search the app reads the LED count set in WLED and says so if the
   LED layout of the app has a different number.

### 2. LED Configuration
- **Per-Side LED Configuration**: You can configure each side separately:
    - Top: 60 LEDs (default)
    - Right: 34 LEDs (default)
    - Bottom: 60 LEDs (default)
    - Left: 34 LEDs (default)
- **LED Layout**: Configure starting corner, direction (clockwise/counterclockwise), and enable/disable individual sides.

### 3. Capture Settings
- **Capture Source**: Choose between **Screen** (default) or **Camera** mode.
    - *Screen*: Standard screen capture using MediaProjection API (requires screen capture permission).
    - *Camera*: Alternative method using device camera with perspective correction. Ideal for TVs that don't support screen capture or for external phone setups.
- **Capture Rate (FPS)**: Default 30 FPS (options: 10, 15, 24, 30, 60).
- **Capture Quality**:
    - *Low (64px)* — For low-end devices or to reduce latency.
    - *Medium (128px)* — Balanced (Default, Recommended).
    - *High (256px)* — For powerful devices only.
    - *Ultra (512px)* — Maximum quality for high-end devices.
- **Send Average Color**: Enable for maximum performance (sends a single color for the whole strip).

### 3.1. Camera Mode Setup
When using **Camera** as the capture source:
1. Grant **Camera** permission when prompted.
2. Open **Settings** → **Camera Corner Setup**.
3. Position your device so the camera can see the TV screen.
4. Drag the four corner markers (TL, TR, BR, BL) to match the edges of your TV screen.
5. Tap **Save** to store the corner positions.
6. The app will use these corners for perspective correction during capture.

**Tips:**
- Use the live preview on the main screen to calibrate your device position before starting capture.
- Ensure good lighting so the camera can clearly see the TV screen.
- The corner adjustment helps compensate for non-square camera placement relative to the TV.

**Find the TV** in Camera Corner Setup tries to place the corners for you. For four seconds it
watches the frame, telling a lit panel from the room by brightness and a TV from a lamp or a
window by whether the picture changes, then loads the result into the overlay — nothing is
saved until you press Save. The strip is held dark while measuring, otherwise its own glow on
the wall reads as part of the screen.

Treat it as a starting point rather than a finished setting: it outlines the lit picture and
not the panel itself, gets confused in a brightly lit room, and the corners usually need
nudging by hand afterwards. When it finds nothing it says why, and the saved corners stay
untouched; the detailed reason goes to logcat under `AutoFrameDetection` and `CameraEncoder`.

### 3.2. Camera Sleep Mode
Camera capture receives no standby signal from the TV, so a powered-off screen keeps
streaming sensor noise to the strip. The **Camera Sleep Mode** settings group (visible only
when the capture source is set to **Camera**) monitors the calibrated screen area and
suspends the capture pipeline once there is nothing new to display. Disabled by default.

- **Enable sleep mode**: master switch for the feature.
- **Sleep delay (seconds)**: how long the picture must remain blank before the strip turns
  off. Range: 5–3600 seconds, default 120. Lower it for a faster response; raise it if dark
  scenes trigger sleep mode.
- **Blank screen threshold (0–96)**: average brightness of the monitored area at or below
  which the screen is considered blank. Default: 12. Increase it if a powered-off TV is not
  detected.
- **Motion sensitivity threshold (1–64)**: the minimum picture change required to count as
  motion. Default: 4. Increase it if sensor noise repeatedly wakes the strip.
- **Sleep on a static picture**: also suspends frame transmission when the picture stops
  changing (paused video, static menu); the strip retains its last colors. Disabled by
  default, as an extended static scene will freeze the strip's colors.

While in sleep mode, the camera remains bound but is sampled at 5 Hz over a sparse luminance
grid instead of running the full perspective-correction pipeline. Waking requires two
consecutive samples above the threshold, so a single noisy frame cannot flash the strip. All
four settings can be adjusted while capture is running, since they can only be calibrated
against a live feed.

### 4. Smoothing
- **Enable Smoothing**: Enabled by default. Reduces LED flickering.
- **Preset**: "Balanced" (default). Options: Off, Responsive, Balanced, Smooth.
- **Settling Time**: 200 ms (default, range: 50-500 ms).
- **Output Delay**: 2 frames (default, range: 0-10).
- **Update Frequency**: 25 Hz (default, options: 20, 25, 30, 40, 50, 60 Hz).

### 5. Launch
1. Grant **Screen Capture / Casting** permission when prompted.
2. Toggle the button to start the grabber.

### 6. Effects
The palette button on the home screen opens the effects. An effect lights the strip without
screen capture: no permission dialog, and what is on the TV does not matter. Static: solid
color, gradient, movie white (color temperature, 6500 K is the reference white). Animated:
rainbow, color cycle, breathing, candle, fire, aurora, ocean, plasma, comet. **Layout test**
paints the sides in different colors and runs a white dot from the first LED, which makes a
wrong LED layout obvious.

**Keep on when the TV screen is off** leaves the effect running as a night light while the TV
box stays awake. Some TVs cut the network in standby, the strip goes dark then anyway.

### 7. Lighting delay
Players and sources process video differently, so the strip can run ahead of the picture by
a different amount in every app. Settings → **Lighting delay** holds a delay for all apps and
a list of apps with their own value; the TV switches to it while that app is on screen. The
per-app part needs usage access, which is granted together with the autostart permissions
via ADB.

**Tune with the camera** runs from a phone paired with the TV. The phone films the TV and the
glow on the wall for about a minute, detects scene changes in both and finds the shift
between them. The strip goes dark for a few seconds at the start while the phone looks for the
screen in the frame. It needs a dark room, a dynamic scene and the whole screen with some wall
around it in the frame. The result comes with an accuracy estimate and is saved for the app
that is playing or for all apps. A strip that is behind the picture cannot be fixed by a
delay: lower the smoothing or pick a faster capture method.

---

### Controller-Specific Details

#### Hyperion
- **Host/Port**: Server IP and port (default `19400`).
- **Priority**: `100` (default).
- [Hyperion Documentation](https://docs.hyperion-project.org/)

#### WLED
- **Host**: Controller IP.
- **Port**: `4048` (DDP), `19446` (UDP Raw, WLED's Hyperion input) or `21324` (UDP Raw, WLED's own realtime port).
- **Protocol**: DDP (Preferred).
- **Color Order**: Ensure this matches your WLED settings (e.g., GRB for WS2812B).
- **RGBW**: works over DDP at any strip length, and over UDP Raw on port `21324` (DRGBW, up to 367 LEDs). Port `19446` is WLED's Hyperion input: raw RGB only, no white channel, capped at 490 LEDs.
  The app extracts the white channel itself (the component common to R, G and B is moved to W), so in WLED the strip type must be RGBW and **Calculate white channel from RGB** must be set to `Manual only` or `Dual`. In the automatic modes WLED discards the white value it receives and recomputes it from the already-reduced RGB, which comes out as zero: the white LEDs stay dark and the colors look washed out.
- [WLED Documentation](https://kno.wled.ge/)

#### E1.31 and Art-Net
- **Start universe**: the first universe of the strip. A strip longer than **LEDs per
  universe** continues into the next universes.
- **LEDs per universe**: 170 fills all 510 channels; set the receiver's value if it differs.
- E1.31 with an empty address is sent as multicast to `239.255.x.y`.

#### tpm2.net, UDP raw, Open Pixel Control
- tpm2.net splits the frame into numbered packets, the size is set in LEDs per packet.
- UDP raw has no header at all, so the whole strip must fit into one datagram (490 LEDs).
- OPC keeps a TCP connection to port `7890`; channel 0 addresses every channel of the server.

#### Philips Hue
- Pick the bridge in the device search or enter its address, then press the round button on
  the bridge and **Pair** within 30 seconds.
- If the Hue app has entertainment areas, the app offers to use one. The bridge then gets a
  DTLS stream on port `2100` up to 25 times per second, and every light of the area takes the
  color of its place set in the Hue app: left to right, and floor, TV level or ceiling. An area
  holds up to 10 lights. While the stream is on, other scenes and automations of the bridge
  do not touch these lights.
- Bridges paired by version 1.x of the app have no streaming key, such a bridge has to be
  paired again to use an area.
- Without an area the lights of the bridge are listed in **Lights and zones**; each one gets a
  screen zone. Colors are converted to the gamut of each light. The bridge takes about ten
  commands per second for all lights, so the update rate is shared between them.

#### Nanoleaf
- Hold the power button on the controller for 5 to 7 seconds until its light flashes, then
  press **Pair** within 30 seconds.
- Every panel takes the color of the place it occupies on the wall, relative to the others.

#### WiZ, Yeelight, LIFX, Govee
- The lights are found on the local network, no cloud and no account.
- Yeelight needs **LAN Control** enabled in the Yeelight app. The app switches every Yeelight
  to music mode, which lifts the limit of 60 commands per minute.
- Govee needs **LAN Control** enabled for the device in the Govee Home app. The LAN API sets one
  color per device, segments of a strip are not addressed separately.

#### Zigbee2MQTT
- The address and port (`1883` by default) are those of the MQTT broker Zigbee2MQTT works with,
  for example the Mosquitto add-on of Home Assistant. Login, password and base topic
  (`zigbee2mqtt` by default) are set in the connection settings.
- The list of lights comes from the retained `zigbee2mqtt/bridge/devices` message, only devices
  that expose a light are shown. Colors are published to `zigbee2mqtt/<name>/set`.
- Zigbee is slow for fast scenes: a few updates per second per light is what the network takes
  without delays.

#### Adalight (USB)
- **Baud Rate**: `115200` (default) or match your firmware. HyperSerial firmwares usually run
  at `2000000`.
- **Protocol**: ADA (Standard Arduino), LBAPA (APA102), AWA (HyperSerial), tpm2, Skydimo,
  SEDU, KarateLight (8 or 16 channels), AtmoLight (5 channels), SP616E.
- [Adalight Repository](https://github.com/adafruit/Adalight)

##### Arduino Sketch for Adalight

A ready-to-use Arduino sketch compatible with the app is available in [`adalight-sketch.ino`](adalight-sketch/adalight-sketch.ino).

**Quick Start:**

1. **Install FastLED library:**
    - In Arduino IDE: `Tools` → `Manage Libraries` → search for "FastLED" → install

2. **Configure the sketch:**
    - Open `adalight-sketch/adalight-sketch.ino`
    - Modify constants at the top of the file:
        - `DATA_PIN` — pin for LED strip connection (default 6)
        - `NUM_LEDS` — number of LEDs in the strip (must match app settings!)
        - `LED_TYPE` — LED strip type (WS2812B, WS2811, SK6812, etc.)
        - `COLOR_ORDER` — color order (GRB for WS2812B, RGB for others)
        - `BRIGHTNESS` — brightness (0-255)

3. **Wiring:**
    - LED strip DATA → Arduino pin (default 6)
    - LED strip VCC → 5V Arduino (or external power supply for long strips)
    - LED strip GND → GND Arduino
    - **Important:** For long strips (>10 leds), use an external 5V power supply!

4. **Upload sketch:**
    - Connect Arduino to computer via USB
    - Select board and port in Arduino IDE
    - Upload the sketch

5. **Connect to Android:**
    - Disconnect Arduino from computer
    - Connect Arduino to Android device via USB OTG cable
    - In the app, select connection type: **Adalight**
    - Set Baud Rate: **115200**
    - Select protocol: **ADA**
    - Ensure LED count in the app matches `NUM_LEDS` in the sketch

**WS2812B Wiring Example:**
```
WS2812B DATA → Pin 6 Arduino
WS2812B VCC  → 5V Arduino (or external 5V)
WS2812B GND  → GND Arduino
```

**For other LED types:**
- **APA102 (SPI)**: Use FastLED library with `APA102` configuration and **LBAPA** protocol in the app
- **WS2811**: Similar to WS2812B, usually `COLOR_ORDER = RGB`
- **SK6812**: Similar to WS2812B, usually `COLOR_ORDER = GRB`

**Troubleshooting:**
- If LEDs don't light up: check wiring, ensure `NUM_LEDS` matches in sketch and app
- If colors are wrong: change `COLOR_ORDER` (try RGB, GRB, BRG)
- If no data received: check Baud Rate (should be 115200), ensure USB OTG cable supports data transfer

## Android TV Features
This application is fully optimized for Android TV, including support for the Leanback Launcher and D-pad navigation. For easier text entry (IP addresses), we recommend using the "Google TV" or "Android TV Remote" app on your phone.

## Phone Remote
The lighting on a TV can be controlled from a phone running the same app: turn it on and off and change any setting, including in the middle of a movie in another app. Color, brightness and LED layout apply immediately; controller address, protocol and smoothing apply within a second without restarting the capture.

1. On the TV: home screen → **Control from a phone** (or Settings → Remote control). The screen shows a QR code, the address and the pairing code.
2. On the phone: home screen → **TV remote** → **Scan QR code**. If the camera cannot read it, type the address and the code manually.
3. The phone remembers the TV and reconnects on the next launch. **Disconnect** on the home screen switches the phone back to its own lighting.

The phone and the TV must be on the same local network. The connection is encrypted with AES-256-GCM using the key from the QR code; **Change the pairing code** on the TV disconnects all phones. While access is on, the TV keeps a background service with a notification: without it the phone could not turn the lighting on when the app on the TV is closed.

The phone is also handy for setting up ADB on the TV itself: the 6-digit code from Wireless debugging is typed on the phone, and the TV finds the pairing port by itself.

Camera corners are dragged on the TV itself; from the phone only the automatic screen search is available.

**Remote** on the phone's home screen turns it into a TV remote: D-pad, Back, Home, volume and
playback buttons, a touchpad that moves a cursor on the TV (a tap clicks, two fingers
scroll), a text field whose input appears on the TV right away, and a list of TV apps to
launch. Buttons, mouse and text go through ADB on the TV; without ADB only system buttons,
taps and text through the accessibility service are available (GitHub and RuStore builds).

## Auto-start
**Grab on Boot** (on by default) brings the lighting back after the TV boots, wakes from sleep or the app updates, if the lighting was on before. After sleep a system alarm watchdog restarts it, so it also comes back on firmware that unloads apps while the TV sleeps.

Capture methods without MediaProjection (Scrcpy/ADB, Screencap, Accessibility, Camera) need nothing else. MediaProjection on Android 10+ requires an on-screen confirmation, and Android will not show it from the background until the app holds permissions that can only be granted over ADB. After pairing ADB, open Settings → **Autostart permissions** → **Grant via ADB**; from then on the lighting starts without dialogs.

If the firmware force-stops the app while the TV sleeps, nothing can bring it back until the next boot: this is a firmware limitation.

## External Control (KeyMapper, Tasker, remote buttons)
The app exposes a transparent toggle activity that any automation tool able to start an activity can use — for example [KeyMapper](https://github.com/keymapperorg/KeyMapper) to bind a remote button:

- Component: `com.vasmarfas.UniversalAmbientLight/com.vasmarfas.UniversalAmbientLight.common.ToggleActivity`
- Without an action it toggles: stops the light if it is running, otherwise starts it.
- With action `com.vasmarfas.UniversalAmbientLight.action.TURN_ON` it only starts, with `com.vasmarfas.UniversalAmbientLight.action.TURN_OFF` it only stops.

Test from adb:

```
adb shell am start -n com.vasmarfas.UniversalAmbientLight/com.vasmarfas.UniversalAmbientLight.common.ToggleActivity
adb shell am start -a com.vasmarfas.UniversalAmbientLight.action.TURN_OFF
```

Starting still shows the system screen-capture dialog unless a capture method that does not need MediaProjection is selected (Accessibility, Scrcpy/ADB, Screencap, Camera).

## Important Notes

### TCL TV Users
On TCL devices, aggressive system battery optimization may kill background services.
**Solution:**
1. Go to **Settings > Apps > Special App Access > Auto-Start**.
2. Enable Auto-Start for Universal Ambient Light.
3. Alternatively, check the "Safety Guard" app and add the app to exceptions.

### Fire TV
If the start button does nothing on a Fire TV Stick, grant the overlay and screen capture permissions over adb. On the Stick enable **Developer options > ADB debugging**, connect from a computer with `adb connect <stick-ip>:5555` and run:

```
adb shell appops set com.vasmarfas.UniversalAmbientLight SYSTEM_ALERT_WINDOW allow
adb shell appops set com.vasmarfas.UniversalAmbientLight PROJECT_MEDIA allow
adb shell am force-stop com.vasmarfas.UniversalAmbientLight
```

Tested on Fire TV Stick HD with Fire OS 7.7.1.6. The Accessibility capture method needs Android 11 and does not work on Fire OS 7 (Android 9).

### High-Quality Video Playback (4K/HDR)
Playback issues with high-quality video (2K/4K/HDR) while the ambient light is active are a hardware limitation of many TVs. Built-in processors often cannot handle simultaneous heavy video decoding and screen capturing. This is a deep-seated issue that is rarely fixable via software.

**If you experience video stuttering or lag:**
1. Lower the video playback quality to 1080p or 720p (depending on your TV's capabilities).
2. Or completely disable the ambient light application while watching high-resolution content.
3. You can try adjusting capture quality and FPS settings, but it is unlikely to fully solve the issue.

**Black preview where the video should be (4K/HDR, HEVC/VP9 — or *all* video on some TVs):**
If your Hyperion/WLED/HyperHDR preview shows a black rectangle where the video should be — while menus, thumbnails, photos and the in-app test colors capture fine — this is **not** a DRM issue. On Android the video is drawn on a dedicated **hardware video plane (overlay)** that is composited by the display hardware and bypasses SurfaceFlinger; `MediaProjection` only sees what SurfaceFlinger composes, so the video region comes out solid black. Usually this affects only 4K / HEVC 10-bit / HDR10 / Dolby Vision while regular 1080p goes through the normal stack — **but some devices route _all_ video through the overlay** (e.g. several Sony models), and **on others it depends on the codec rather than the resolution**: on Amlogic boxes HEVC/VP9/AV1 playback comes out black at any resolution while H.264 (AVC) captures perfectly, because those decoders hand AFBC-compressed frames straight to the video plane. A black *local* file rules out DRM. This is a platform limitation that also affects every other Android ambient-light tool (Hyperion Android, Lightpack apps, etc.) — there is no fix for the standard `MediaProjection` path, and the `Scrcpy` / `Screencap` / `ADB` methods sit behind SurfaceFlinger too, so they show the same black area.

Workarounds:
1. **Force H.264 (AVC)** if only HEVC/VP9/AV1 goes black: in SmartTube pick the AVC stream instead of VP9; in Jellyfin disable HEVC/AV1 direct play for the client (or cap the bitrate) so the server transcodes to H.264.
2. **Drop the playback to 1080p** in the player/server (Jellyfin → set max bitrate / resolution per device; Kodi → adjust playback settings; mpv → `--hwdec=no` plus `--vo=gpu`). 1080p almost always goes through the regular composition stack.
3. **Disable hardware decoding** in the player (Jellyfin Android: Settings → Player → toggle off "Prefer FMP4" and "Allow background audio playback"; in mpv → `--hwdec=no`). Software decode forces the frame through the composition path, restoring capture.
4. **For rooted devices** — try `Screencap (Root)` or `Screencap (Shell)` capture method in Settings → these go through the `screencap` binary instead of `MediaProjection`, which helps with some capture restrictions; note that `screencap` also reads SurfaceFlinger's output, so it does **not** recover a hardware video plane.
5. **On MediaTek SoC TVs** the experimental `MTK THAL Capture` method captures directly from the vendor DIP engine (before composition), and 4K HDR works there — but requires both root and an MTK chip.

### DRM-Protected Content
**DRM-protected applications** (such as Netflix, Disney+, Amazon Prime Video, Кинопоиск, and similar streaming services) **will not work** with screen capture mode due to Android's security restrictions. This is a fundamental limitation of the Android MediaProjection API and cannot be bypassed.

**Why this happens:**
- Android blocks screen capture of DRM-protected content to prevent piracy.
- This is enforced at the system level and cannot be overridden by applications.

**Solutions:**
1. **Use Camera Mode**: Switch to **Camera** capture source in settings. This method uses the device camera instead of screen capture, so it works with DRM-protected content. You'll need to position a phone/tablet with the camera facing the TV screen.
2. **Disable Ambient Light**: Turn off the ambient light feature while watching DRM-protected content.
3. **Use Non-DRM Sources**: Watch content from sources that don't use DRM protection (local files, YouTube, etc.).

**Note:** Camera mode requires proper calibration of corner positions for accurate color capture.


## License
See [LICENSE.txt](LICENSE.txt)

## Contributing
Contributions are welcome! Please feel free to submit Pull Requests or Report Issues.

