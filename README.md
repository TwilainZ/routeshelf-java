# GDirect Lite — Android prototype

> **Unofficial experimental prototype.** Independent Android implementation inspired by [mizorewww/gdirect](https://github.com/mizorewww/gdirect). Not endorsed by its author or Google.
>
> **No APK has been built or device-tested in this workspace** because the Android SDK/Gradle distribution are unavailable here. The project includes an automated build workflow that can compile `app-debug.apk` on GitHub Actions.

## 功能 / Features

- Android 10 (API 29) 或更新版本，**不需要 Root**。
- 原生 Java Android UI，无第三方 Android UI / 网络库依赖。
- 粘贴或从其他 App 分享一个**公开的 Google Drive 文件**链接。
- 扫描 Google IP 并在**保持 HTTPS 证书验证和真实主机名 SNI**的情况下，尝试指定 IP 连接；也可留空，沿用系统网络。
- 1 / 4 / 8 HTTP Range 并行下载；4 MiB 分块，完成分块会本地保存进度，重新下载相同链接可恢复。
- 前台下载服务、进度通知；完成后保存到系统 `下载/GDirect Lite` 目录，可从 App 打开。
- **不需要 Google 登录，不申请云盘读写权限，不运行任何代理服务器，也不上传用户数据到我们的服务器。**

## 构建 APK

### 方式 A：GitHub Actions（最适合没有 Android Studio 的情况）

1. 在自己的 GitHub 仓库中上传本目录的**文件和子目录**，保留 `.github/workflows/build-apk.yml`。
2. 打开仓库的 **Actions → Build Android APK → Run workflow**（或者推送代码自动触发）。
3. 等待 workflow 成功，打开对应的工作流运行记录，在 **Artifacts** 下载 `GDirectLite-debug-APK`。
4. 解压得到 `app-debug.apk`，传给安卓手机并安装。

GitHub Actions 在构建期间下载 Android SDK、Gradle 和依赖；需要网络，并非离线构建。

### 方式 B：Android Studio

使用较新的 Android Studio，安装 Android SDK API 35、JDK 17，并打开本项目目录。使用 Gradle 8.9 构建：

```sh
gradle :app:assembleDebug
```

APK 默认在 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要下载 Android Gradle Plugin 8.7.3。

## 使用方法

1. 复制一个公开的单文件 Google Drive 分享链接（例如 `https://drive.google.com/file/d/FILE_ID/view`）。
2. 启动 App，粘贴链接。
3. 可先点击“扫描可用 IP”。如果测试失败，**清空 IP 地址**，让应用使用系统网络。使用 VPN/TUN 时，扫描结果也可能受其路由方式影响。
4. 选择并行连接数，点击“开始下载”；完成后在系统下载目录找到文件。
5. 下载取消后，再次使用同一个链接，若服务器支持 HTTP 206，已完成的分块可复用。

## 已知限制

- **仅公开、单个普通文件**：不支持 Google Workspace 文档导出、文件夹、私人文件登录、云盘上传或挂载。
- Google 下载页面可能有复杂的确认按钮、动态令牌、病毒扫描确认或流量配额限制；已实现简单 HTML 表单确认，但不保证所有 Google Drive 页面都能解析。
- 分块续传是按**完整的 4 MiB 分块**保存进度，取消时进行中的分块会重新下载；无 HTTP Range 的服务器走单连接，不能续传。
- 使用标准 SNI 和严格的 HTTPS 主机名验证；**尚未实现原项目的自定义 SNI / 无 SNI 模式和修改过的 aria2 引擎**。指定 IP 只是正常 HTTPS 连接的 DNS/IP 替换，不能保证绕过网络限制，更不应视为 VPN。
- 多连接实现是 Android/Java 的 HTTP Range 下载器，**不是原版 gdirect Rust + patched aria2 的 Android 移植**。某些 Google CDN 节点可能不支持强制 IP。
- 尚未经过完整的编译、模拟器与真机测试。性能、电池、设备兼容性、安全性均有待验证。
- 前台数据传输受 Android 系统的后台执行/时长限制；建议在 App 前台使用。
- 进度通知权限在 Android 13+ 下载时申请；即使拒绝也可使用前台服务，但通知可见性受系统规则限制。
- 默认在本机 App 私有存储保留下载中临时文件和分块进度；下载完成后复制到系统 Downloads，可能暂时需要约两份文件大小的存储空间。
- 应用代码不从 Google 账户读取数据，不会创建可疑证书或停用 HTTPS 验证。

## 技术概要

- `app/src/main/java/dev/gdirect/lite/DirectNet.java`: Google Drive 链接解析、HTTPS、IP 测试、跳转处理、Google HTML 确认。
- `app/src/main/java/dev/gdirect/lite/DownloadService.java`: 前台下载、多连接、断点续传、MediaStore 下载保存。
- `app/src/main/java/dev/gdirect/lite/MainActivity.java`: 手机界面、分享入口、任务控制。
- `.github/workflows/build-apk.yml`: 自动构建可安装的 Android debug APK。

## 许可证

这个独立 Android 原型以 **GPL-2.0-or-later** 开源，以方便之后与原版 gdirect 协作或合并。参见 `LICENSE`。原作者及 Google 与此非官方原型没有关联。

## 自动发布到独立 GitHub 私有仓库（Mac / Linux）

如果当前聊天无法直接创建 GitHub 仓库，可以在电脑上安装 `git` 和 GitHub CLI (`gh`)，运行一次 `gh auth login`，然后在解压后的 `GDirectLiteAndroid` 目录执行：

```sh
bash publish-and-build.sh
```

脚本只会创建一个**新的私有仓库** `gdirect-lite-android`，**不会修改其他已有仓库**；如同名仓库已存在，命令会失败，不会覆盖它。`push` 会触发 `.github/workflows/build-apk.yml`；生成的 APK 在 Actions 的 Artifacts 中下载。

这并不意味着已在本地或云端编译成功。下载后的 `app-debug.apk` 是仅用于个人测试的 debug 包，正式分发需额外签名、安全审计和设备测试。
