# GeoWave Vision AR V4

Native Android app for sensor scanning, on-device object detection, and symbolic audio playback. Supports Android 8.0 and later. AR features require a supported device with Google Play Services for AR. The app uses CameraX and records unlocated sensor measurements when ARCore is unavailable. A device with no accessible camera can still record sensor readings.

## Using the app

- **Scan** starts a capture and lets you choose the measurement used for the world-space heat map. Walk through the area to collect readings along your route. Samples represent the phone's position when collected. Use **Save scan** to store the latest snapshot internally or **Export CSV** to choose an external destination.
- **Targets** lets you place a marker on a tracked surface, select individual or multiple markers, or select all markers. Choose one marker and tap **Move selected target**, then tap the new surface. Object placement depends on available ARCore depth or feature points. Anchors persist for the active AR session and do not follow independently moving objects.
- **Audio** plays the included five-second RF Clean Reset symbolic profile or an imported RIFF WAV file. The timer caps playback. Repeat loops the profile until the timer ends. Emergency stop releases playback immediately. The rings are visual effects around selected anchors. Playback is ordinary audible sound.
- The language control switches between English and Thai.

## Measurement and export

Vector readings use Android device sensor axes. The heat map uses magnitudes for magnetic field, acceleration including gravity, and angular speed. Light readings are illuminance in lux. Microphone input is windowed at 16 kHz and reports RMS in dBFS and the strongest FFT bin in Hz. This is a digital signal level. The microphone buffer is timestamped on read completion. Sensor readings are time-stamped individually, and empty fields indicate missing data. Positions use meters in the local ARCore frame. The session identifier distinguishes different local frames after a restart.

## Build

Use JDK 17, Gradle 8.10.2, Android SDK Platform 35, and Build Tools 35.0.0.

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

The Android debug APK workflow runs the same Gradle tasks, verifies the debug signature, and uploads `GeoWave_Vision_AR_V4.apk` to the workflow run artifacts.
