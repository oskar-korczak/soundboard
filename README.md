# Soundboard

Android soundboard app with HTTP server.

## Wireless Deployment (ADB over WiFi)

The tablet (Huawei MediaPad JDN2-L09) runs Android 9, so it uses ADB TCP/IP mode.

### First-time setup (cable required once)

1. Plug in the USB cable
2. Enable TCP mode:
   ```
   adb tcpip 5555
   ```
3. Find the tablet's IP (currently `192.168.38.109`):
   ```
   adb shell ip route
   ```
4. Unplug the cable and connect wirelessly:
   ```
   adb connect <tablet-ip>:5555
   ```

### Subsequent deploys (no cable)

```
adb connect 192.168.38.109:5555
./gradlew installDebug
```

### Notes

- TCP mode does not persist across tablet reboots — you'll need to plug in and run `adb tcpip 5555` again after a reboot.
- The tablet's IP may change if the network assigns a new one.
