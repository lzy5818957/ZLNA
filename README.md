# ZLNA

**English** · [简体中文](README.zh-CN.md)

Share a video folder on your Android phone with a DLNA player such as SKYBOX on Meta Quest 3S. Connect both devices to the same Wi-Fi, or connect the headset to your phone’s hotspot. ZLNA serves the original files over the local network.

## Watch a video

1. Install the [Android APK](https://github.com/lzy5818957/ZLNA/releases/download/v0.2.2/ZLNA-0.2.2.apk) on a phone running Android 8.0 or later.
2. Connect your phone and headset to the same network. The phone’s hotspot also works. Use 5 GHz when available.
3. Open ZLNA, choose a local folder such as Movies or DCIM, and tap **Start**.
4. In SKYBOX, open **Network**, refresh, select **ZLNA**, and choose a video. VLC on iPhone can also browse the server.
5. Tap **Stop sharing** in the app or its notification when finished.

ZLNA remembers the selected folder. Each time you tap Start, it scans that folder and its subfolders. Stop and start again to refresh the library after adding files. Android’s folder picker may restrict storage roots and protected folders. ZLNA requests read-only access to the selected folder.

Other devices on the same local network can read the shared videos without a password while the server is running. Stopping sharing closes the connections. A player may briefly continue playing buffered content.

The interface supports English and Simplified Chinese and follows the phone’s language. On Android 13 and later, you can also choose a language specifically for ZLNA in the system’s app language settings.

## Background sharing and the cover screen

On first launch, **Background sharing & permissions** shows the folder grant, notification status and Android battery optimization exemption. On Xiaomi phones, open ZLNA’s battery settings and select **Unrestricted** under the per-app battery policy. The notification is optional and provides a shortcut to stop sharing.

To test screen-off playback, start sharing, turn the screen off for at least one minute, then open a video or seek to an unbuffered position from another device. ZLNA records success when it actually sends video data to that device. Check **Screen-off test** in the guide. Stopping sharing clears the result for that session.

On **Xiaomi MIX Flip 2**, open **Settings → Outer screen → Outer screen apps** on the inner screen. Search for ZLNA and tap the green plus. After folding, wake and unlock the cover screen and open the ZLNA icon. Start and Stop are above the folder controls in this layout. Use the inner screen if the system folder picker or permissions dialog requests it.

## Compatibility

ZLNA implements SSDP discovery, UPnP MediaServer and its ContentDirectory and ConnectionManager services, paginated browsing, event subscriptions, HTTP GET and HEAD, and single byte ranges for seeking. It detects IPv4 interfaces on Wi-Fi or a hotspot and advertises address changes automatically. A foreground service handles sharing while the app is in the background. The server stops if the OS force-stops the app.

Videos, embedded subtitles and audio tracks are delivered as stored. Codec, resolution, bitrate and VR playback support depend on the player and network. The library supports seekable local files. External subtitles, thumbnails, playlists, Blu-ray folder parsing, IPv6 and transcoding are outside the current scope. The app implements DLNA/UPnP protocols and has no DLNA certification.

MIX Flip 2 cover-screen controls and screen-off byte-range reads have been tested with a Mac client. Playback, seeking and sustained throughput in SKYBOX on a physical Quest 3S still need headset testing. [Test records](TESTING.md)

## Build and test

Use JDK 17, Android SDK 36 and Build Tools 35.0.0. Set `ANDROID_HOME` or set `sdk.dir` in a local `local.properties` file. The app and protocol core have no third-party runtime dependencies.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

The locally built APK is under `app/build/outputs/apk/debug/app-debug.apk`. Gradle signs debug builds with the developer’s local key. A build signed with a different key cannot update an existing installation in place.

With ZLNA running on a phone and a computer on the same network, run the Python 3 standard-library probe. It discovers the server, reads the directory and fetches up to three 64 KiB video ranges.

```sh
python3 scripts/probe.py
python3 scripts/probe.py --interface 192.168.43.2
```

To test a known endpoint, pass the device description URL shown under **Connection help → View diagnostics** using `--url`. Emulator-only test procedures and the headset acceptance checklist are in the [test records](TESTING.md).

- `app/` contains the Android UI, folder reader and foreground service.
- `core/` contains the HTTP, SSDP and UPnP implementations and their protocol tests.
- `scripts/` contains the network and emulator checks.

## License

ZLNA is available under the [MIT License](LICENSE). Anyone may use, modify, redistribute or sell the software, subject to retaining the copyright and permission notice in copies or substantial portions. The bundled Gradle Wrapper retains its [Apache 2.0 license](gradle/wrapper/LICENSE).
