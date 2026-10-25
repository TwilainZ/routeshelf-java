# GDirect Lite — Android prototype

> **独立开发的 Android 下载工具实验项目。** 非 Google 官方应用。
>
> **当前状态：** v0.1.0 已通过 GitHub Actions 构建 APK，并已完成公开 Google Drive 文件下载的手机实测；其他网络与设备场景尚未充分测试。

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

1. 此仓库根目录保存 `GDirectLiteAndroid-source.zip`，工作流 `.github/workflows/import-and-build.yml` 会自动解压并编译。
2. 上传 ZIP 或修改工作流后会自动运行，也可以从仓库 **Actions** 页面查看运行记录。
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
- 使用标准 SNI 和严格的 HTTPS 主机名验证；**当前不支持自定义 SNI / 无 SNI 模式，也没有集成 aria2 引擎**。指定 IP 只是正常 HTTPS 连接的 DNS/IP 替换，不能保证绕过网络限制，更不应视为 VPN。
- 多连接实现是独立的 Android/Java HTTP Range 下载器；某些 Google CDN 节点可能不支持强制 IP。
- v0.1.0 已通过云端编译和一次公开文件下载的真机测试；其他机型、性能、电池与安全性仍有待验证。
- 前台数据传输受 Android 系统的后台执行/时长限制；建议在 App 前台使用。
- 进度通知权限在 Android 13+ 下载时申请；即使拒绝也可使用前台服务，但通知可见性受系统规则限制。
- 默认在本机 App 私有存储保留下载中临时文件和分块进度；下载完成后复制到系统 Downloads，可能暂时需要约两份文件大小的存储空间。
- 应用代码不从 Google 账户读取数据，不会创建可疑证书或停用 HTTPS 验证。

## 技术概要

- `app/src/main/java/dev/gdirect/lite/DirectNet.java`: Google Drive 链接解析、HTTPS、IP 测试、跳转处理、Google HTML 确认。
- `app/src/main/java/dev/gdirect/lite/DownloadService.java`: 前台下载、多连接、断点续传、MediaStore 下载保存。
- `app/src/main/java/dev/gdirect/lite/MainActivity.java`: 手机界面、分享入口、任务控制。
- `.github/workflows/import-and-build.yml`: 从仓库内源码 ZIP 构建 Android debug APK。

## 许可证

本项目的 Android 源码使用 **GPL-2.0-or-later** 许可证，参见源码 ZIP 内的 `LICENSE`。
