# Privacy Policy

**Effective Date:** 2026-01-26

**Universal Ambient Light** ("we," "our," or "us") is committed to protecting your privacy. This Privacy Policy explains how we collect, use, and safeguard your information when you use our mobile application (the "Service").

## 1. Information Collection and Use

### Screen Data (Media Projection)
Our Service uses the Android Media Projection API (`FOREGROUND_SERVICE_MEDIA_PROJECTION`) to capture the colors displayed on your screen. 
*   **Purpose:** This data is used solely in real-time to calculate the average colors for controlling your ambient lighting hardware (e.g., USB or Network-connected LEDs).
*   **Privacy:** This screen data is processed locally on your device in volatile memory (RAM). It is **never** saved to storage, recorded, or transmitted to any external server or third party.

### USB and Network Communication
The app communicates with external hardware (LED controllers) via USB (`android.hardware.usb.host`) or local network.
*   **Data Transmitted:** Only color values and control commands are sent to your configured hardware.
*   **No External Tracking:** We do not track or log the devices you connect to.

### Storage Access
We may request access to your device's storage (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`).
*   **Purpose:** To save and load your personal configuration files (settings, LED profiles) and, if enabled by you, to write debug logs.
*   **Scope:** We only access files related to the application's operation.

## 2. Permissions

To function correctly, the app requires specific permissions:
*   **Display over other apps (`SYSTEM_ALERT_WINDOW`):** To function in the background while you use other apps.
*   **Foreground Service:** To keep the ambient light service running effectively.
*   **Boot Completed:** To automatically start the service when you turn on your device (if enabled in settings).
*   **Post Notifications:** To show the persistent notification required for the foreground service.
*   **Request Install Packages:** To allow the app to update itself if you are using a version distributed outside of the Play Store (e.g., GitHub Releases).

## 3. Third-Party Services

This Service does not use third-party analytics or advertising frameworks (like Google AdMob or Firebase Analytics) that track your behavior.
The app is open-source, and its code is available for audit.

## 4. Children’s Privacy

Our Service does not address anyone under the age of 13. We do not knowingly collect personally identifiable information from children under 13.

## 5. Security

We value your trust. Since we do not collect personal data or transmit it to cloud servers, the risk of data breach is minimal. All processing happens locally on your device.

## 6. Changes to This Privacy Policy

We may update our Privacy Policy from time to time. Thus, you are advised to review this page periodically for any changes. We will notify you of any changes by posting the new Privacy Policy on this page.

## 7. Contact Us

If you have any questions or suggestions about our Privacy Policy, do not hesitate to contact us.

**Developer:** vasmarfas
**Contact:** https://github.com/vasmarfas/universal-ambient-light/issues
