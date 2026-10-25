# GDirect Lite Android

受 [mizorewww/gdirect](https://github.com/mizorewww/gdirect) 的功能与使用思路启发，基于现有 Java Android 原型继续开发的 Google Drive 客户端。应用代码独立实现，没有移植该项目的源码、aria2 补丁或原生二进制；非 Google 或该项目作者的官方应用。

## 当前版本：0.2.0 开发预览

Android 10（API 29）及以上，无需 Root。

- **公开链接下载**：保留原型的链接解析、HTTPS / IP 测试、前台下载和系统 Downloads 集成。公开文件无需登录。
- **安全续传**：仅在服务器提供强 ETag 时进行分块/续传，使用 `If-Range` 并核对 Content-Range 和文件版本；没有可靠版本标识时改用单流，避免把不同版本拼接起来。
- **Google 授权**：通过 Google 官方 Android `AuthorizationClient` 请求云盘只读权限；令牌只在应用进程内存中使用，不请求服务器离线访问或刷新令牌。
- **私人文件浏览**：My Drive 根目录、文件夹、当前文件夹搜索和分页；支持断开本机连接及撤销 Google 授权。
- **私人普通文件下载**：单流、前台进度、取消；在 Drive 提供校验值时核对 MD5，并核对文件大小后保存到 `下载/GDirect Lite`。私人请求不会使用手填 IP，也不跟随 HTTP 重定向。

**验证范围：** 原 v0.1.0 曾由维护者在手机上成功下载公开文件。当前版本的构建、解析检查和协议/安全单元测试可以复现；本版本的真实 Google 账号授权及私人文件下载仍需配置 Cloud 项目并按 [验证清单](docs/GOOGLE_SETUP.md) 实测。不要把历史手机测试当成本版本的完整功能验证。

## 构建与测试

Android Studio 打开仓库根目录即可。命令行需要 JDK 17、Android SDK API 35 和 Build Tools 34.0.0 / 35.0.0；设置 `ANDROID_HOME` 或本机未跟踪的 `local.properties`。首次构建会联网下载依赖。

```sh
bash tests/run-java-checks.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2
```

Gradle Wrapper 固定 8.9，并校验分发包 SHA-256。Android Gradle Plugin 固定 8.7.3。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，测试与 Lint 报告位于 `app/build/reports/`。

[GitHub Actions](.github/workflows/build-apk.yml) 对 `main` / `dev/**` 推送和 PR 直接构建源码，执行检查并上传 `GDirectLite-debug-APK`。默认 CI debug 签名是临时签名，不能当作正式发布签名。

## Google 登录配置

按 [GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md) 启用 Drive API、设置同意屏幕和测试用户，注册包名 `dev.gdirect.lite` 与实际 APK 签名 SHA-1 的 **Android OAuth 客户端**。需要带 Google Play 服务的设备。

应用不需要嵌入 Desktop/Web 客户端密钥或账户密码。`drive.readonly` 是 restricted scope；公开发布前需按 Google 当时政策完成适用的权限验证、隐私政策和安全评估。

## 使用

公开文件：粘贴 Google Drive 单文件链接，IP 留空即可使用系统网络；可选 1 / 4 / 8 条连接，在服务器提供稳定版本标识时启用分块。取消后重试只复用仍匹配该文件版本的完整分块。

私人文件：打开“我的 Google Drive”，点击连接，完成 Google 的账号选择/授权；进入文件夹或搜索，点击普通文件的“下载到设备”。返回下载页可查看进度、取消和打开已完成文件。菜单提供撤销授权及许可证声明。

## 当前限制与下一步

- 私人下载暂不跨会话续传；断开连接会使旧请求失效。进程退出后需重新点击连接，由官方 SDK 重新获取授权令牌。
- 暂不支持 Google 文档导出、共享云端硬盘、快捷方式解析、上传、挂载或下载队列。后续可参考 gdirect 的功能体验，按 Android 平台约束独立设计实现。
- Google 公开链接的确认页、resource key、下载配额与网络环境并不统一；解析不能保证覆盖所有情形。保持严格 TLS 验证，不将 IP 测试视为 VPN。
- 不实现自定义 SNI、关闭主机名校验或证书伪造。私人 OAuth/API 流量始终遵循系统网络。
- Android 对前台数据同步服务有启动及运行时长限制；大文件建议保持应用前台。Android 13+ 会请求通知权限。
- 下载临时文件位于应用私有存储/缓存，发布到系统下载目录时可能需要接近两倍文件大小的空间。已保存的下载不会因断开 Google 授权而删除。

## 工程与来源

- `app/`：正式 Android 源码及 JVM 单元测试。
- `tests/`：不需要 Android 设备的独立 Java 解析检查。
- `archive/`：原始 v0.1.0 ZIP，保留字节及原 GPL 许可，仅供历史参考，不参与构建。
- [许可与来源审查](docs/LICENSING.md)：维护者授权改用 Apache-2.0 的记录。
- [第三方声明](THIRD_PARTY_NOTICES.md)：Google SDK、AndroidX、Kotlin 及工具/测试依赖的许可；新增依赖后运行 `python3 tools/refresh-notices.py` 并复核。

当前应用自有源码采用 [Apache License 2.0](LICENSE)。第三方组件仍保留其自己的条款，Google SDK 不是本项目重新授权的 Apache 代码。功能灵感不意味着代码移植，也不影响维护者决定自主实现的后续方向。
