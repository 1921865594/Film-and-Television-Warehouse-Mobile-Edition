# 影视仓(手机端)

<p align="center">
  <strong>面向 Android 手机的 TVBox 竖屏客户端 · 二次开发增强版</strong><br />
  <sub>Portrait-first Android TVBox client · Enhanced community fork</sub>
</p>

<p align="center">
  <a href="https://www.gnu.org/licenses/agpl-3.0.html"><img src="https://img.shields.io/badge/License-AGPL--3.0-blue.svg" alt="AGPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Language-Java-orange.svg" alt="Java" />
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-lightgrey.svg" alt="arm64-v8a" />
</p>
### 界面
![首页](https://github.com/1921865594/_Film-and-Television-Warehouse-Mobile-Edition/blob/main/%E6%88%91%E7%9A%84%E9%A1%B5%E9%9D%A2.png)
![我的页面](https://github.com/1921865594/_Film-and-Television-Warehouse-Mobile-Edition/blob/main/%E9%A6%96%E9%A1%B5.png)

**默认中文说明。** [English](#english)

---

## 项目简介

影视仓 是一款面向 Android 手机的 TVBox 风格影视客户端，采用手机优先的竖屏交互方式。项目保留 TVBox 生态中常见的数据源配置、点播解析、直播、短剧、搜索、弹幕和多播放器能力，并围绕触摸屏使用场景调整首页、详情页、播放器和设置页面。

本仓库是在 **[Gladglod/MBox](https://github.com/Gladglod/MBox)** 的基础上进行的二次开发。该上游项目说明其自身基于 [TVBoxOS](https://github.com/q215613905/TVBoxOS) 改造。因此，本项目的代码来源关系为：

```text
TVBoxOS
   └── Gladglod/MBox（手机竖屏版）
          └── 本仓库 影视仓（二次开发增强版）
```

本项目不是 Gladglod/MBox 或 TVBoxOS 的官方发布版本。这里的“增强版”指本仓库相对于所提供的 `MBox-main` 原始源码所做的代码修改，具体变化见下文。

### 项目定位

- **客户端，不是影视内容服务器。** 点播、直播和解析内容取决于用户配置的数据源及其服务端；项目不保证第三方源持续可用，也不替第三方内容提供版权授权。
- **手机优先。** 页面以触摸操作和竖屏浏览为主要设计目标，播放器仍支持横屏全屏等场景。
- **Java/XML 原生界面。** 当前仓库没有 Kotlin 源文件，也没有 Jetpack Compose 页面；界面主要由 Java、XML 布局和 Android View 组成。
- **以 ARM64 为当前构建目标。** 当前 Gradle 配置仅打包 `arm64-v8a`，不再提供原始工程中的 32 位 ABI / Python 构建变体。

## 功能概览

### 基础能力

| 模块 | 功能说明 |
| --- | --- |
| 点播 | 支持配置点播数据源、分类浏览、搜索、影片详情、选集和播放解析；支持 JavaScript / JAR 爬虫加载机制。 |
| 直播 | 支持直播源配置、频道分组、节目单（EPG）及直播播放设置。 |
| 短剧 | 支持短剧源管理、短剧分类与短剧内容浏览。 |
| 播放器 | 集成 ExoPlayer 相关组件及项目内播放器模块，保留 IJK 等原有播放能力；支持倍速、画面比例、字幕、音轨/视频轨、弹幕及投屏相关功能。具体可用能力受设备、媒体格式和数据源影响。 |
| 弹幕 | 支持弹幕显示参数、自定义弹幕接口及接口测试。 |
| 数据管理 | 支持播放历史、收藏、本地文件入口和数据源配置管理。 |
| 扩展能力 | 保留 QuickJS 爬虫运行环境、网络请求、DNS over HTTPS（DoH）等基础组件。 |

### 手机端交互

- 首页采用顶部分类页签与分页内容布局，底部提供首页、短剧、直播和个人设置等入口。
- 热门推荐支持 **豆瓣热播、当前数据源推荐、本地播放历史** 三种展示模式。
- 首页分类可管理显示状态及顺序；底部短剧、直播入口可按需隐藏。
- 详情页顶部提供内嵌预览播放器，选集横向排列，并支持正序/倒序切换。
- 播放器支持单击显示/隐藏控制栏、双击播放/暂停或快进/快退、横向拖动进度、左右区域亮度/音量调节，以及竖屏上下滑动切集等手势。部分手势可在设置中关闭或调整。
- 播放器设置面板根据横竖屏采用侧边抽屉或底部抽屉形式，集中管理解码、画面比例、片头片尾、弹幕、字幕、音轨和解析线路。
- 「我的」页面采用手机端分组菜单，集中管理数据源、主题、播放历史、收藏、播放设定、界面偏好、播放手势、弹幕和备份同步。

## 相对于原始 MBox-main 的二次开发内容

以下内容按本次提供的 `MBox.zip` 与 `MBox-main.zip` 源码差异整理。这里区分了已有能力的增强与本次新增的功能，避免把上游原本就有的 TVBox 基础能力误写成本分支原创。

### 一、已有模块的升级与优化

| 模块 | 本次升级内容 | 相关代码位置 |
| --- | --- | --- |
| 首页推荐 | 增加独立的豆瓣热播请求与解析逻辑；豆瓣列表使用当前点播站点搜索进入详情，不把豆瓣 subject ID 当作站点视频 ID；推荐数据增加 15 分钟内存缓存。 | `SourceViewModel.java`、`HomeActivity.java` |
| 首页刷新 | 热门推荐新增下拉刷新；分类列表调整手势拦截方式，直接监听 RecyclerView 触摸事件，改善父容器无法完整收到下拉手势的问题。 | `HotRecommendFragment.java`、`GridFragment.java` |
| 推荐模式 | 将首页推荐来源明确区分为豆瓣热播、站点推荐和本地历史；站点推荐模式不再错误地用本地播放历史填充站点推荐空列表。 | `HawkConfig.java`、`HomeActivity.java` |
| 点播线路 | 识别线路集合后不再自动切换到第一条线路；在数据源页面展示线路列表，由用户明确选择后再加载，降低自动选错线路的风险。 | `ApiConfig.java`、`ApiSettingActivity.java` |
| 数据源历史 | 新增点播配置备注、线路来源映射、历史地址归并及去重；减少线路 URL 被错误保存成独立数据源历史的情况。 | `HistoryHelper.java`、`ApiSettingActivity.java` |
| 配置备份 | 增加独立 JSON 配置快照导入/导出；先解析和校验备份内容，再覆盖配置白名单；支持随备份保存和恢复自定义壁纸。 | `BackupUtil.java`、`MineSettingActivity.java` |
| DNS 稳定性 | DoH 关闭、配置为空、索引越界、URL 无效或构建失败时不再强行创建无效 DNS 客户端，改为回退 Android 系统 DNS。 | `OkGoHelper.java` |
| JavaScript 引擎 | QuickJS 初始化移动到爬虫上下文创建前，并增加进程内一次性初始化保护；QuickJS Android wrapper 同步升级。 | `JsSpider.java`、`quickjs/build.gradle` |
| 启动诊断 | 在 Application 早期启动阶段安装诊断记录，记录崩溃栈、设备信息、Activity 生命周期及当前进程 logcat，方便定位启动和运行问题。 | `GlobalCrashLogger.java`、`App.java`、`LOG.java` |
| 设置与导航 | 新增底部短剧/直播入口的隐藏选项，并在隐藏当前所在页面时自动回到首页。 | `MineSettingActivity.java`、`HomeActivity.java` |

### 二、本次新增的功能

#### 1. 网盘凭据管理

新增「我的 → 网盘 Token」页面，当前覆盖以下凭据类型：

| 网盘 | 管理内容 |
| --- | --- |
| 阿里云盘 | `refresh_token` |
| 夸克网盘 | 登录 Cookie |
| UC 网盘 | 登录 Cookie |
| 百度网盘 | 登录 Cookie |

支持手动粘贴导入、查看是否已配置和复制凭据；也可以通过应用内 WebView 打开对应登录页面，在登录后尝试自动读取对应 Token / Cookie。

**安全提示：** 凭据保存在应用私有 `SharedPreferences` 中。当前实现没有为该存储增加 Android Keystore 加密层，因此 Token / Cookie 应按敏感信息管理，不要将其提交到 Git、公开截图或随意发送给他人。配置 JSON 备份不会包含这些网盘凭据。

#### 2. TVBox 推送端口二维码扫描

新增「我的 → 扫一扫」入口：

1. 请求相机权限并扫描二维码；
2. 识别 TVBox 推送端口二维码中的 IP:端口或 HTTP(S) 地址；
3. 校验地址后打开内置 WebView 页面；
4. 端口页面提供刷新、错误重试、本地点播配置历史查看，以及相关配置/凭据的快捷复制入口。

该功能面向 TVBox 推送端口二维码，不是通用条码管理工具。访问目标设备时，手机需要能够通过当前网络访问二维码中的地址。

#### 3. 配置备份与同步

「我的 → 备份与同步」可将配置导出为 JSON 文件，并通过系统文件选择器选择保存位置；也可从 JSON 文件导入配置。

备份范围包括数据源地址与线路、EPG / DoH、直播源、短剧源、主题与自定义壁纸、首页布局和分类顺序、播放设定、字幕、界面偏好、播放手势及弹幕设置等。

以下内容**不包含在该 JSON 配置备份中**：

- 播放历史、收藏及其他媒体数据库内容；
- 网盘登录 Token / Cookie；
- 不在备份配置白名单中的运行时状态。

导入采用配置白名单完整覆盖语义：备份文件中缺失的白名单配置会被清除并回到应用默认值。导入前请先确认文件来源，并建议保留一份当前配置备份。

#### 4. 全局崩溃与运行诊断

新增 `GlobalCrashLogger`：

- 尽早安装未捕获异常处理器，记录致命异常和堆栈；
- 记录应用版本、Android 版本、设备型号、ABI 等设备信息；
- 记录 Activity 生命周期及应用初始化阶段标记；
- 捕获当前进程 logcat，单次采集上限为 8 MiB；
- 日志写入应用私有目录 `files/diagnostics/`，每次进程启动时清理该目录中的旧 `.log` 文件。

诊断日志可能包含设备信息及应用运行期间产生的日志。向他人提交日志前，请先检查是否包含个人信息、访问地址或其他敏感内容。

## 开发环境与构建依赖

以下版本以当前仓库 Gradle 配置为准。

| 项目 | 版本 / 配置 |
| --- | --- |
| JDK | **17**（运行 Gradle / Android Gradle Plugin） |
| Android Gradle Plugin | **9.5.0** |
| Gradle Wrapper | **9.6.0** |
| Android Compile SDK | **API 37** |
| Android Build Tools | AGP 9.5.0 默认版本 **36.0.0**；如本机配置不同，以 Gradle Sync 提示为准 |
| minSdkVersion | **21**（Android 5.0） |
| targetSdkVersion | **28** |
| Java source / target compatibility | **Java 8** |
| 构建变体 | `java`（仅 Java 构建变体） |
| Native ABI | **arm64-v8a** |
| applicationId | `com.github.mbox.osc.dong` |
| Android namespace | `com.github.tvbox.osc` |
| versionName / versionCode | `3.0` / `3` |

### 主要依赖组件

| 类别 | 主要依赖 |
| --- | --- |
| Android UI | AndroidX AppCompat 1.3.0、RecyclerView 1.2.1、ViewPager2 1.0.0、Material 1.4.0、ConstraintLayout 2.0.4、SwipeRefreshLayout 1.1.0 |
| 图片与列表 | Glide 4.16.0、Picasso 2.71828、BaseRecyclerViewAdapterHelper 2.9.45、LoadSir 1.3.8、AutoSize 1.2.1 |
| 网络 | OkHttp 3.12.11、OkGo 3.0.4、Cronet OkHttp adapter 0.1.0、Cronet Embedded 101.4951.41、Brotli 0.1.2 |
| 播放 | ExoPlayer 2.19.1、Jellyfin FFmpeg Extension 2.19.1+1、DKPlayer UI 3.3.5，以及项目内 IJK / Native 播放组件 |
| JavaScript | QuickJS Android wrapper 3.2.3 |
| 数据与解析 | Room 2.3.0、Hawk 2.0.1、Gson 2.10.1、XStream 1.4.15、Jsoup 1.14.1 |
| 其他 | ZXing 3.3.0、DanmakuFlameMaster 0.9.25、NanoHTTPD 2.3.1、Cling 2.1.2、XXPermissions 13.6 |

此外，`app/libs/` 中还包含项目直接引用的本地 JAR / AAR；播放器模块内含 `arm64-v8a` 预编译 Native 库。当前工程没有配置需要单独编译的 C/C++ `externalNativeBuild` 任务，也没有在 Gradle 中锁定 NDK 版本。

### 构建 Debug APK

在项目根目录执行：

**Windows**

```bat
gradlew.bat :app:assembleJavaDebug
```

**macOS / Linux**

```bash
chmod +x gradlew
./gradlew :app:assembleJavaDebug
```

默认 Debug APK 输出位置：

```text
app/build/outputs/apk/java/debug/MBox_debug-java.apk
```

构建 Release：

```bash
./gradlew :app:assembleJavaRelease
```

Release APK 默认输出位置：

```text
app/build/outputs/apk/java/release/MBox_release-java.apk
```

> **发布前必须检查签名配置：** 当前 `app/build.gradle` 的 `release` 构建类型使用 `signingConfigs.debug`。这适合当前工程调试流程，不应直接视为正式发布签名。公开分发前，请配置自己的 Release keystore，并妥善保管签名文件与密码。

### 运行与调试

```bash
# 查看系统崩溃缓冲区
adb logcat -b crash -d

# 查看 Activity / 应用崩溃事件
adb logcat -b events -d | grep am_crash

# 查看弹幕加载链路
adb logcat -s echo-danmu

# 查看本应用诊断日志标签
adb logcat -s MBox-Diagnostic
```

应用私有诊断文件目录为：

```text
/data/user/0/com.github.mbox.osc.dong/files/diagnostics/
```

不同 Android 版本及设备上，实际数据目录可能因系统实现而略有不同；普通文件管理器通常无法直接访问该私有目录。

## 项目结构

```text
MBox/
├── app/                      # Android 应用：界面、业务逻辑、数据源、设置、资源
│   ├── libs/                 # 本地 JAR / AAR 依赖
│   ├── schemas/              # Room 数据库 Schema
│   └── src/main/
│       ├── assets/           # JS 库、EPG 数据等资源
│       ├── java/             # Java 业务代码
│       └── res/              # XML 布局、图片、字符串、主题等资源
├── player/                   # 播放器封装及 arm64-v8a Native 库
├── quickjs/                  # QuickJS Android 桥接模块
├── gradle/wrapper/           # Gradle Wrapper
├── build.gradle              # 根工程构建配置
├── settings.gradle           # 模块配置
└── gradle.properties         # Gradle / AndroidX / AGP 项目设置
```

**构建变体说明：** 原始 `MBox-main` 工程包含 `pyramid` / Chaquopy 相关 Python 模块和多种 32 位、64 位构建变体；当前仓库的 `settings.gradle` 已移除 `pyramid`，`app` 仅保留 `java` flavor，播放器 Native 库和 ABI 过滤也仅保留 `arm64-v8a`。因此，不能再使用旧工程的 `assembleJava64Debug`、`python32` 或 `python64` 等任务名。

## 数据源配置

点播和直播内容由用户自行配置。数据源一般通过「我的 → 数据源地址」导入或填写；应用保留 TVBox 风格的配置结构，常见字段包括：

```json
{
  "spider": "./your.jar",
  "wallpaper": "./api/img",
  "sites": [],
  "parses": [],
  "hosts": [],
  "lives": [],
  "rules": [],
  "doh": []
}
```

常用字段说明：

| 字段 | 作用 |
| --- | --- |
| `spider` | 爬虫 / Spider JAR 地址或路径 |
| `wallpaper` | 数据源壁纸地址 |
| `sites` | 点播站点列表 |
| `parses` | 播放解析线路 |
| `hosts` | 域名与 IP 映射规则 |
| `lives` | 直播源配置 |
| `rules` | 嗅探或代理规则 |
| `doh` | DoH DNS 配置 |

实际字段格式、请求方式和可用性以所使用的数据源规范为准。请仅使用自己有权访问和使用的服务与内容。

## 已知限制与注意事项

1. **仅支持 ARM64 APK。** 只提供 `arm64-v8a` ABI；仅支持 32 位 ARM 的设备不在当前构建目标内。
2. **最低 Android 版本为 API 21。** 工程中部分播放器子模块声明的 minSdk 较低，但最终应用 flavor 的最低版本为 API 21。
3. **Python 爬虫构建已移除。** 当前工程为 Java flavor，不包含原始工程中的 `pyramid` / Chaquopy Python 构建链路；QuickJS JavaScript 爬虫仍保留。
4. **网盘凭据属于敏感数据。** 当前存储使用应用私有 SharedPreferences，未见额外的 Keystore 加密封装；不要公开分享凭据或含凭据的调试信息。
5. **配置备份不是完整设备迁移包。** 它不包含播放历史、收藏、媒体数据库和网盘凭据；导入会覆盖配置白名单，建议导入前先备份当前设置。
6. **第三方接口存在变动风险。** 豆瓣热播、用户配置的数据源、解析接口、网盘登录站点等均依赖外部服务，其接口、访问限制或服务状态变化可能影响相关功能。
7. **Release 签名需自行配置。** 当前 Release 构建使用 Debug 签名配置，正式发布前必须替换。
8. **构建尚未在此处执行验证。** 本 README 的环境与任务名称来自本次源码配置检查；实际构建仍取决于本机 Android SDK、网络仓库可用性和所用 IDE / 构建环境。

## 开源协议与致谢

本仓库包含的 `LICENSE` 文件为 **GNU Affero General Public License v3.0（AGPL-3.0）**。本次检查的优化版与原始 `MBox-main` 中的 `LICENSE` 文件内容一致，因此这里保留原有协议，不擅自更换许可证，也不虚构原作者或版权归属。

- 许可证全文：[LICENSE](./LICENSE)
- 协议正文：[GNU AGPL v3.0](https://www.gnu.org/licenses/agpl-3.0.html)
- 直接上游：[Gladglod/MBox](https://github.com/Gladglod/MBox)
- 上游基础项目：[TVBoxOS](https://github.com/q215613905/TVBoxOS)

根据 AGPL-3.0 分发本项目或其修改版本时，应保留适用的版权声明、许可证文本和上游归属信息，并履行相应的源代码提供义务。若将修改后的程序作为网络服务向用户提供交互，还应特别阅读 AGPL-3.0 第 13 条关于远程网络交互的要求。第三方依赖、预编译 Native 库及本地 JAR / AAR 仍须分别遵守其各自许可证；本项目的 AGPL 声明不会自动替代这些依赖的许可条款。

> 本节为项目开源信息说明，不构成法律意见。若要公开发布二进制包或提供在线服务，请逐项核对上游和第三方组件的版权声明与许可证要求。

---

## English

### Overview

MBox is an Android TVBox-style media client designed primarily for portrait-oriented phones. It retains the TVBox-style source configuration and playback architecture while adapting browsing, video details, player controls, and settings for touch interaction.

This repository is a community-maintained derivative of [Gladglod/MBox](https://github.com/Gladglod/MBox). The direct upstream project describes itself as being based on [TVBoxOS](https://github.com/q215613905/TVBoxOS). This repository is not an official release of either upstream project.

### Main capabilities

- Video-on-demand browsing, source configuration, search, detail pages, episode selection, and playback parsing.
- Live TV channels, groups, EPG data, and playback preferences.
- Short-drama source management and browsing.
- Multiple playback components, danmaku, subtitles, playback gestures, and casting-related features.
- Three home recommendation modes: Douban hot titles, the active source's recommendations, and local watch history.
- User-selectable API lines, named source-history entries, and normalized/deduplicated API history.
- QR scanning for TVBox push-port addresses and an embedded WebView for the scanned endpoint.
- Credential management for Aliyun Drive, Quark, UC, and Baidu Netdisk. Credentials can be imported, copied, or acquired through the in-app login WebView.
- JSON configuration backup and restore, including custom wallpaper assets. Watch history, favorites, media databases, and cloud-drive credentials are excluded.
- App-private crash and runtime diagnostics, including exception stacks, device metadata, Activity lifecycle events, and a capped current-process logcat capture.

### Development environment

| Item | Configuration |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 9.5.0 |
| Gradle Wrapper | 9.6.0 |
| Compile SDK | Android API 37 |
| Build Tools | 36.0.0 (AGP default) |
| minSdk / targetSdk | 21 / 28 |
| Java compatibility | Java 8 source and target |
| Build flavor | `java` only |
| ABI | `arm64-v8a` only |
| Application ID | `com.github.mbox.osc.dong` |

Build a debug APK from the project root:

```bash
./gradlew :app:assembleJavaDebug
```

The default output is `app/build/outputs/apk/java/debug/MBox_debug-java.apk`.

**Release-signing note:** the current `release` build type uses the debug signing configuration. Configure a private release keystore before distributing a production APK.

### License

This repository includes the **GNU Affero General Public License v3.0 (AGPL-3.0)** in [`LICENSE`](./LICENSE). Preserve the applicable upstream copyright and license notices when redistributing this project or modified versions. Third-party libraries and bundled binaries remain subject to their own licenses. See the [full AGPL-3.0 text](https://www.gnu.org/licenses/agpl-3.0.html), especially Section 13 for network interaction with modified versions.
