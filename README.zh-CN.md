# ZLNA

[English](README.md) · **简体中文**

让安卓手机与 Meta Quest 3S 连接同一 Wi-Fi，即可通过 SKYBOX 浏览手机视频的极简本地媒体服务器。也支持手机开热点、Quest 连接热点，无需额外配置或切换模式。

## 使用

1. 安装 [ZLNA 0.2.2 的 Android APK](https://github.com/lzy5818957/ZLNA/releases/download/v0.2.2/ZLNA-0.2.2.apk)（Android 8.0 及以上）。从之前的测试版覆盖更新会保留已选文件夹。
2. 让手机和 Quest 3S 连接同一 Wi-Fi；或者由手机开启热点，让 Quest 连接它。建议选择 5 GHz 网络。
3. 打开 ZLNA，选择本地视频文件夹，点击 **运行**。
4. 在 SKYBOX 的 **Network** 页面刷新，进入 **ZLNA**，选择影片播放。
5. 结束时点击 **停止共享**，也可以从通知栏停止。

ZLNA 随系统语言显示简体中文或英文。Android 13 及以上也可在系统的「应用语言」里单独选择 ZLNA 的语言。

文件夹选择会保留。运行会重新扫描子目录，新增或移动视频后停止再运行即可刷新。Android 的文件选择器不允许授权整个内部存储根目录、Download 根目录或 Android/data；请选 Movies、DCIM 或自己新建的视频子目录。没有全盘存储权限、登录、账户或云服务。通知权限可以拒绝，仍可回到应用停止共享。

运行时同一局域网的设备可读取所选视频，不设密码。应用只读取文件，不修改媒体。关闭应用界面后仍用前台服务维持共享；点击停止会关闭监听和正在播放的连接，并释放唤醒锁。系统强制结束应用后不会自行重启共享。

**后台共享与权限**：首次使用会显示引导，之后可在主页重新打开。点击「打开 ZLNA 电池设置」，在小米应用信息的「省电策略」中把 **ZLNA** 设为 **无限制**，再返回应用。引导会重读 Android 电池优化豁免、后台限制、文件夹只读授权和通知状态。厂商的「无限制」单选项请在系统页核对；它能改善后台运行，不能保证应用永远不会被系统结束。只有媒体文件夹授权是运行所必需；通知是可选的。

**实测熄屏播放**：开始共享后熄屏至少 1 分钟，再让另一台设备播放或拖到未缓冲的位置。回到「后台共享与权限」查看「熄屏实测」。只有 ZLNA 在屏幕持续熄灭后向其他设备实际发出新请求的视频数据，才显示「本次已验证」；仅发现服务器、读取目录或播放缓存不会算作通过。停止共享会清除本次结果。

**MIX Flip 2 外屏**：先在内屏打开「设置 → 外屏 → 外屏应用」，搜索 ZLNA，点绿色加号。若「设置 → 外屏 → 合盖显示」使用「直接息屏」，合盖后点亮并解锁，从外屏桌面打开 ZLNA 即可，不必更改锁屏习惯。窄屏上「运行 / 停止共享」排在文件夹之前。文件夹选择和系统设置如要求展开，请使用内屏。若系统未列出 ZLNA，可以先在内屏开始共享，合盖后从外屏通知查看状态。真机结果见[验证记录](TESTING.zh-CN.md)。

## 当前范围

- SSDP M-SEARCH、alive/byebye；自动发现热点和 Wi-Fi 的 IPv4 网卡，每 5 秒检查地址变化。
- 可解析的 MediaServer:1 设备描述、ContentDirectory:1、ConnectionManager:1。
- BrowseMetadata / BrowseDirectChildren、分页、按标题排序、正确转义 DIDL-Lite。
- GENA 初始事件、续订、取消订阅。目录为每次运行的快照。
- HTTP GET / HEAD，单范围 Range（206 / 416）、大于 4 GiB 的偏移、独立并发读取。
- SAF 只读文件夹授权、前台 connectedDevice 服务、Wi-Fi 组播锁和运行期间的唤醒锁。
- 界面内可查看并复制有限长度的本次进程诊断日志。

直接传输原视频，不转码。视频编码、音轨、3D/VR 格式由 SKYBOX 决定支持情况。当前不提供外置字幕、缩略图、播放列表、ISO 蓝光目录解析、IPv6 或自动扫描媒体变更；文件内嵌音轨/字幕随原文件传输。只支持可定位读取的本地文件，不支持云端虚拟文件。采用 UPnP/DLNA 互通协议，未申请 DLNA 认证；不承诺所有路由器、安卓厂商省电策略或所有播放器都兼容。

## 构建与检查

使用 JDK 17、Android SDK 36、Build Tools 35.0.0。Gradle Wrapper 固定为 8.13，AGP 为 8.13.2。应用和协议核心无第三方运行时依赖。

```sh
# 设置本机 JDK；在 local.properties 中写 sdk.dir=/你的/Android/sdk
export JAVA_HOME=/你的/jdk17/Contents/Home
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。debug APK 用本机开发密钥签名，适合侧载测试；正式发布需要独立保管的发布签名密钥。

`core` 是纯 Java 协议模块，自动化测试通过真实 TCP 连接验证设备 XML、SOAP / DIDL、HEAD、字节范围、6 GiB 文件偏移、并发定位、事件订阅、安全边界和停止连接。

在 Android 模拟器安装应用并选择测试文件夹后，可运行 `python3 scripts/check_lifecycle.py --adb /你的/adb`。它验证应用内停止、通知栏停止、再次启动、进程未崩溃以及前台服务/唤醒锁释放，仅允许模拟器设备。

手机运行 ZLNA 后，让电脑连接同一 Wi-Fi（或手机热点），用 Python 3 标准库脚本检查实际链路。该脚本仅执行发现、读取目录并取最多三段各 64 KiB 的视频数据：

```sh
python3 scripts/probe.py
# 有多个网卡时，指定电脑当前 Wi-Fi 地址：
python3 scripts/probe.py --interface 192.168.43.2
# 或用应用「连接帮助 → 查看诊断」中的设备描述地址：
python3 scripts/probe.py --url http://192.168.43.1:端口/description.xml
```

### 真机验收

必须在实际手机和 Quest 上，分别用同一 Wi-Fi、手机热点两种连接方式确认，模拟器或电脑测试不能替代这些检查：

- SKYBOX Network 能出现 ZLNA，进入多层目录并播放一个已知支持的视频。
- 片头、中间、片尾拖动可恢复播放；大于 4 GiB 的影片定位正确。
- 播放 15 分钟并熄灭手机屏幕，观察连接和带宽稳定性。
- 停止后正在播放的连接关闭；播放器已缓冲画面可能短暂继续。
- 切换 Wi-Fi、重开热点或更换局域网地址后能重新发现；拒绝通知权限仍能从应用停止。

## 结构

- `app/`：原生安卓界面、SAF 扫描与随机读取、前台服务与诊断。
- `core/`：独立的 SSDP、HTTP、SOAP / DIDL、GENA，便于在电脑上重现协议问题。
- `scripts/probe.py`：实际设备链路检查。

设计依据：[UPnP MediaServer / ContentDirectory / ConnectionManager 标准](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver1-and-mediarenderer1/)、[Android 文件夹授权](https://developer.android.com/training/data-storage/shared/documents-files)、[connectedDevice 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)、[本地网络访问规则](https://developer.android.com/privacy-and-security/local-network-permission)。targetSdk 36 使用 INTERNET 权限访问本地网络；升级至 targetSdk 37 时需要适配新的本地网络运行时权限。

## 开源许可

ZLNA 使用 [MIT 许可证](LICENSE)。任何人都可以使用、修改、分发或商用，分发副本或实质性部分时请保留版权和许可声明。随仓库分发的 Gradle Wrapper 使用它自己的 [Apache 2.0 许可证](gradle/wrapper/LICENSE)。
