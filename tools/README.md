# 开发工具

| 入口 | 用途 |
| --- | --- |
| `bash tools/check.sh` | 独立检查、两变体 JVM 测试、Android 编译与 Lint |
| `bash tools/build-preview-apk.sh` | 使用仓库外保留的测试版私钥打包并核验身份；不生成替代密钥 |
| `python3 tools/verify-debug-apk.py --help` | APK 包名、版本、提交、签名、资源和关键代码检查 |
| `bash tools/benchmark-relay-startup.sh 1820e38` | 本地合成前置等待对照，无公网探测 |
| `python3 tools/refresh-notices.py [--module inspector]` | 解析依赖并更新声明；没有新增依赖也不要随意改许可证 |

`checks/` 保存独立 Java 检查源码与运行器。`legacy-inspector/` 是旧主包的离线安装身份工具，项目名仍是 `:inspector`。临时 debug 密钥生成脚本已退出流程并移除；交付只用固定预览签名。设置 JDK/SDK 见 [开发说明](../docs/DEVELOPMENT.md)。

日常 CI 不发布旧检查器 APK；只有手动运行工作流并勾选 publish_legacy_inspector 才生成其下载工件，避免与当前客户端混淆。
