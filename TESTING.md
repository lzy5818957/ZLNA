# ZLNA test records

**English** · [简体中文](TESTING.zh-CN.md)

## 0.2.2 bilingual release

The signed, non-debuggable 0.2.2 APK was installed on an Android API 36 emulator with a previously granted video folder. Sharing started in English. Switching the app language to Simplified Chinese during sharing kept the same process and endpoint. A Mac fetched an HTTP 206 video range before and after switching, and the Chinese Stop button ended sharing. Both locales passed the permission guide and compact-window resize tests. The external video-read test after a minute of screen-off time passed. All 17 core tests passed and Android Lint reported no issues.

Physical MIX Flip 2 results below were collected on the listed earlier releases. The published APK uses the same signing certificate as 0.2.1.

## MIX Flip 2 on a phone hotspot

These checks used a physical Xiaomi MIX Flip 2 running Android 17, a Mac on the phone’s hotspot, and ZLNA sharing a DCIM folder. Each network probe opened fresh connections.

| Version and condition | Observation |
| --- | --- |
| 0.1.1, screen on | The Mac discovered ZLNA, browsed 14 video entries (one empty), and received correct 206 responses for the beginning, middle and end of a 336,348,311-byte MP4. An iPhone VLC user reported seeing the videos. |
| 0.1.1, screen off, restricted battery policy | Discovery and TCP connections timed out in three runs while the phone still answered ping. Unlocking restored service on the same port without restarting ZLNA. |
| 0.1.1, screen off, unrestricted battery policy | All seven probes at ten-second intervals over one minute passed. A separate throttled transfer received 8 MiB over one connection in 31.9 seconds. |
| 0.2.0 | An in-place update retained the folder and notification settings. The new setup guide opened the correct battery settings page. Sharing continued after folding, but this build was absent from Xiaomi’s cover-screen app list. |
| 0.2.1 | The updated app appeared in the cover-screen app list. It opened from the cover launcher and showed 14 shared videos. Stop closed the old listener and Start announced a new working endpoint, all on the cover screen. |
| 0.2.1, folded, screen off | Fresh probes at 0, 30 and 65 seconds each passed browse, HEAD and three 64 KiB ranges. Discovery was repeated at 0 and 65 seconds. After unlocking, the in-app screen-off test displayed its verification time. |

## Automated coverage

- The Java protocol suite exercises real TCP requests, device and service XML, SOAP browsing and pagination, Chinese titles and XML escaping, event subscription, shutdown, invalid ranges and concurrent reads. It also checks offsets in a synthetic 6 GiB file, path traversal and rejection of external XML entities.
- On an API 36 emulator, earlier releases persisted an actual folder-picker grant, indexed nested folders and read a marker near the end of a 5,368,709,243-byte sparse local file. Start/stop checks exercised app and notification controls and checked that the foreground service and wake lock were released.
- Setup and compact-layout instrumentation checks cover stale grants, refreshing the displayed battery exemption after returning from Settings, retaining the guide during resize, keeping the stop button visible and preserving the running server.
- The screen-off integration check uses a real external TCP read after a minute. Early reads, HEAD and device description requests do not trigger verification. A later video range does, and stopping clears the result.

## Reproduce

```sh
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# On a computer connected to the same Wi-Fi or hotspot as the running phone:
python3 scripts/probe.py
# On a dedicated API 36 emulator with the app installed and a local video folder selected:
python3 scripts/check_lifecycle.py --adb /path/to/adb --serial emulator-5554
# This check requires an emulator named ZLNA_API36 and the installed androidTest APK:
python3 scripts/check_screen_off.py --adb /path/to/adb --serial emulator-5554
```

## Headset acceptance work

Use a physical Quest 3S on both shared Wi-Fi and the phone hotspot to verify discovery, playback and audio, seeking near the beginning, middle and end, and a high-bitrate video with the phone’s screen off for at least 15 minutes. Check how SKYBOX responds to a server stop and a Wi-Fi address change. These headset checks remain open.
