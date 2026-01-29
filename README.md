<img width="1280" height="640" alt="Universal Ambient Light" src="app/src/main/res/drawable/banner.png" />

# Universal Ambient Light

**Universal application for creating ambient screen lighting on Android devices**

Universal Ambient Light is a modern Android application that captures screen content in real-time and sends it to LED controllers to create an immersive ambient lighting experience. The application is fully compatible with Android 8.0+ and supports both mobile devices and Android TV.

**🇷🇺 [Read in Russian / Читать на русском](README_RU.md)**

## ✨ Key Features

- 🎨 **Universal Controller Support**: Compatible with Hyperion, WLED, and Adalight.
- 📱 **Android TV & Mobile Optimized**: Dedicated interfaces for touchscreens and D-pad navigation.
- 🔄 **Auto-Discovery**: Automatically scans the local network for available LED servers.
- ⚙️ **Flexible Configuration**: Adjustable capture quality, frame rate, color smoothing, and latency settings.
- 🚀 **Auto-Start**: Automatically launches the service on device boot.
- 🔌 **Auto-Reconnect**: Robust connection handling with automatic reconnection attempts.
- 📊 **Average Color Mode**: Reduces CPU/Network load by analyzing and sending the dominant screen color.
- 🎯 **Quick Access**: Quick Settings Tile for instant toggling.

## 🎯 Supported Controllers

### Hyperion
- Full Hyperion protocol support.
- Message priority configuration.
- Compatible with all Hyperion NG versions.

### WLED
- Supports **DDP** (recommended for WLED 0.11+) and **UDP Raw** protocols.
- configurable color order (RGB, GRB, BRG, etc.).
- RGBW LED support.
- Brightness control.

### Adalight
- Supports **ADA**, **LBAPA** (LightBerry APA102), and **AWA** (Hyperserial) protocols.
- Configurable Baud Rate.
- USB OTG connection support.

## 📋 Requirements

- Android 8.0 (API 26) or higher.
- Screen Capture permission (MediaProjection).
- Local network access (for Hyperion/WLED) or USB Host support (for Adalight).

## 🚀 Installation

### From GitHub Releases
The latest versions (**TV** and **Mobile**) can be downloaded from the [Releases Page](https://github.com/vasmarfas/universal-ambient-light/releases).

### [Google Play](https://play.google.com/store/apps/details?id=com.vasmarfas.UniversalAmbientLight)

### [RuStore](https://www.rustore.ru/catalog/app/com.vasmarfas.UniversalAmbientLight)

## ⚙️ Configuration

### 1. Connection
1. Launch the app and open **Settings**.
2. Select **Connection Type**: Hyperion, WLED, or Adalight.
3. Enter the IP/Port (for network controllers) or configure USB settings.

### 2. LED Configuration
- **Per-Side LED Configuration**: You can configure each side separately:
   - Top: 60 LEDs (default)
   - Right: 34 LEDs (default)
   - Bottom: 60 LEDs (default)
   - Left: 34 LEDs (default)
- **LED Layout**: Configure starting corner, direction (clockwise/counterclockwise), and enable/disable individual sides.

### 3. Capture Settings
- **Capture Rate (FPS)**: Default 30 FPS (options: 10, 15, 24, 30, 60).
- **Capture Quality**: 
  - *Low (64px)* — For low-end devices or to reduce latency.
  - *Medium (128px)* — Balanced (Default, Recommended).
  - *High (256px)* — For powerful devices only.
  - *Ultra (512px)* — Maximum quality for high-end devices.
- **Send Average Color**: Enable for maximum performance (sends a single color for the whole strip).

### 4. Smoothing
- **Enable Smoothing**: Enabled by default. Reduces LED flickering.
- **Preset**: "Balanced" (default). Options: Off, Responsive, Balanced, Smooth.
- **Settling Time**: 200 ms (default, range: 50-500 ms).
- **Output Delay**: 2 frames (default, range: 0-10).
- **Update Frequency**: 25 Hz (default, options: 20, 25, 30, 40, 50, 60 Hz).

### 5. Launch
1. Grant **Screen Capture / Casting** permission when prompted.
2. Toggle the button to start the grabber.

---

### Controller-Specific Details

#### Hyperion
- **Host/Port**: Server IP and port (default `19400`).
- **Priority**: `100` (default).
- [Hyperion Documentation](https://docs.hyperion-project.org/)

#### WLED
- **Host**: Controller IP.
- **Port**: `4048` (DDP) or `19446` (UDP Raw).
- **Protocol**: DDP (Preferred).
- **Color Order**: Ensure this matches your WLED settings (e.g., GRB for WS2812B).
- [WLED Documentation](https://kno.wled.ge/)

#### Adalight (USB)
- **Baud Rate**: `115200` (default) or match your firmware.
- **Protocol**: ADA (Standard Arduino), LBAPA (APA102), AWA.
- [Adalight Repository](https://github.com/adafruit/Adalight)

## 📱 Android TV Features
This application is fully optimized for Android TV, including support for the Leanback Launcher and D-pad navigation. For easier text entry (IP addresses), we recommend using the "Google TV" or "Android TV Remote" app on your phone.

## ⚠️ Important Notes

### TCL TV Users
On TCL devices, aggressive system battery optimization may kill background services.
**Solution:**
1. Go to **Settings > Apps > Special App Access > Auto-Start**.
2. Enable Auto-Start for Universal Ambient Light.
3. Alternatively, check the "Safety Guard" app and add the app to exceptions.

### High-Quality Video Playback (4K/HDR)
Playback issues with high-quality video (2K/4K/HDR) while the ambient light is active are a hardware limitation of many TVs. Built-in processors often cannot handle simultaneous heavy video decoding and screen capturing. This is a deep-seated issue that is rarely fixable via software.

**If you experience video stuttering or lag:**
1. Lower the video playback quality to 1080p or 720p (depending on your TV's capabilities).
2. Or completely disable the ambient light application while watching high-resolution content.
3. You can try adjusting capture quality and FPS settings, but it is unlikely to fully solve the issue.


## 📄 License
See [LICENSE.txt](LICENSE.txt)

## 🤝 Contributing
Contributions are welcome! Please feel free to submit Pull Requests or Report Issues.

