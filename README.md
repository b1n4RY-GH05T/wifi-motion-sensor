# Redmi Wi-Fi Motion Sensor — prototype v1

Native Android/Kotlin RSSI prototype plus a Python receiver/dashboard for the ThinkPad.

## Important limitations

This version measures ordinary connected-Wi-Fi RSSI (signal strength), **not CSI**. It cannot read raw radio waveforms or locate a person. RSSI can be quantized, updated slowly, and affected by router traffic, device orientation, other clients and interference. A possible-movement alert means only that the signal changed enough to cross an experimental threshold. It is not a reliable security alarm and may miss people or produce false positives.

The Android app samples the current connection once per second. It does not scan for networks. Keep the app open on screen for this first version. Doorway crossing/direction is not implemented: a single RSSI value cannot reliably infer a geometric line.

## Build Android app

1. Install Android Studio on the ThinkPad.
2. Extract this ZIP and open the `Redmi_WiFi_Motion_Sensor` folder in Android Studio.
3. Allow Gradle sync to download the Android Gradle Plugin and Kotlin plugin.
4. Connect Redmi by USB with Developer options and USB debugging enabled, or build an APK using **Build > Build Bundle(s) / APK(s) > Build APK(s)** and transfer it.
5. Install and open **Wi-Fi Motion Sensor**.

Project: compile/target SDK 35, minimum SDK 26, Java/Kotlin 17. No camera, microphone, location or nearby-device permissions are requested.

## Start ThinkPad receiver (Windows)

1. Install Python 3 if needed.
2. Open PowerShell in the extracted `receiver` folder.
3. Run `py receiver.py`.
4. If Windows Firewall prompts, allow Python on **Private networks only**.
5. Find ThinkPad IPv4: run `ipconfig`, then look under the Wi-Fi adapter for IPv4 Address.
6. Open `http://127.0.0.1:8765` on ThinkPad.
7. In Android app enter the ThinkPad IPv4 address and port `8765`.
8. Both devices must be on the same eero LAN (not an isolated guest network).

The phone posts to `http://THINKPAD-IP:8765/api/sample` and `/api/event`. This is unencrypted LAN traffic. Do not port-forward it or expose it through a public tunnel.

## First experiment

1. Fix the phone in place 1–3 metres from the path; keep orientation unchanged.
2. Start receiver, open dashboard, then start monitoring in Android app.
3. Leave area empty for 2 minutes and note normal RSSI fluctuation.
4. Walk across the area repeatedly, then stand still for a minute.
5. Repeat. Try threshold values from 3 to 10 dB.
6. Compare movement-related variation with the empty-room baseline.

If the graph stays flat or movement changes are no larger than normal fluctuations, RSSI on this phone is not useful enough for the intended sensor. Do not assume the alert identifies a person.
