# 安装与连接 Google Drive

当前 Android 版本为开发预览。构建记录在 [GitHub Actions](https://github.com/qingzhuernest/routeshelf-java/actions)，公开发行版会出现在 [Releases](https://github.com/qingzhuernest/routeshelf-java/releases)。

## Android

测试版使用包名 `dev.gdirect.lite.preview`。只有同一包名、同一签名且版本码适合升级的 APK 才能更新现有测试版。下载后核对工件中的 `APK-IDENTITY.json` 和 `SHA256SUMS`，签名指纹以 [测试版身份配置](../config/preview-signing.json) 为准。旧主包 `dev.gdirect.lite` 可以保留。

自行构建时，从 [Android 构建说明](DEVELOPMENT.md) 开始。公开单文件下载无需账号。要访问账号内的文件，自行签名的构建须在自己的 Google Cloud 项目中配置与 APK 匹配的 [Android OAuth 客户端](GOOGLE_SETUP.md)。同一包名和签名的日常更新可继续使用已有配置。

GitHub Actions 中的离线检查器只用于查看旧主包的安装身份。调试 APK、检查器和固定签名的测试版用途不同，请按工件清单选择。

## 下载和反馈

在线分发安装包时，下载位置应同时说明如何取得该构建的对应源码。项目产物的身份清单记录构建提交及其 `sourceURL`。发布前请确认对应源码已可访问，或提供相应的源码归档。详见 [许可证与归属](LICENSING.md#分发安装包)。

连接或传输有问题时，提供版本、系统、连接方式和复现步骤。Android 可以手动导出 [连接日志](DIAGNOSTICS.md)。涉及漏洞或凭据时，参阅 [安全报告](../SECURITY.md)。
