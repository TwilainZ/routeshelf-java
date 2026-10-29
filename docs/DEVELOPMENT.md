# 开发、验证与交付

## 环境

需要 JDK 17、Android SDK Platform 35、Build Tools 34.0.0 和 35.0.0。项目使用固定 Gradle 8.9 Wrapper（含分发 SHA-256 校验）和 Android Gradle Plugin 8.7.3，无需另外安装 Gradle。

Android Studio 打开仓库根目录，安装上述 SDK；或命令行配置 `ANDROID_HOME`。也可在不提交的 local.properties 中设置 sdk.dir。首次构建需要联网下载依赖；云环境应遵守自身网络和 CA 配置，不绕过代理策略。

```sh
bash tools/check.sh
```

这会执行 11 项独立 Java 检查、debug/preview JVM 测试、主 debug APK 构建、两变体 Lint，以及离线检查器构建/Lint。不需要预览私钥，不发布 APK，不改变 Google Cloud。测试报告在 app/build/reports/。当前 0.7.0 各变体 115 项 JVM 测试；测试数量会随开发变化。

## 源码目录

| 路径 | 用途 |
| --- | --- |
| app/src/main/java/dev/gdirect/lite/ | 原生界面、Drive API/会话、连接、上传下载与预览 |
| app/src/main/res/ | 主题、字符串、矢量与启动器资源 |
| app/src/main/resources/ | 公开 GTS 根证书/许可及有来源的内置候选元数据 |
| app/src/test/ | JVM 协议、回环 TLS、状态与安全边界测试 |
| inspector/ | 无 INTERNET 权限的独立旧主包检查器 |
| tests/ | 无 Android 设备/网络的 Java 检查 |
| tools/ | 统一检查、签名打包、APK 身份验证与许可清单 |
| config/ | 公开签名指纹及根证书摘要，不存私钥 |
| docs/ · design/ | 使用/开发文档和应用图标设计记录 |
| archive/ | 原始 GPL Java 原型 ZIP，仅历史参考 |

保持现有 Java 模块的职责边界，不为目录整齐移动协议代码而增加无收益回归风险。后续大型功能再按实际依赖拆分模块。

## 可安装的测试版

唯一现有预览身份为 dev.gdirect.lite.preview，证书见 config/preview-signing.json。普通源码检查不需要密钥；交付必须安全恢复同一保留密钥，设置 GDIRECT_PREVIEW_KEYSTORE_PATH，然后在已提交且干净的工作树执行：

```sh
bash tools/build-preview-apk.sh
```

打包器拒绝缺失/错误密钥，核对实际包名、版本、提交、签名和 GTS 根包，输出至 app/build/outputs/verified-preview/提交/。不要生成替代私钥来覆盖用户已有测试版。主包 dev.gdirect.lite 的旧签名是另一套身份，不能混用。现有维护者已报告完成 Google 配置，同一预览身份更新无需再登记客户端。

私钥不能提交、放入 ZIP、贴在 Issue 或聊天中。云环境当前保留了原密钥；外部备份和 CI Secret 尚未确认，不能宣称长期备份已完成。

## CI 与依赖

GitHub Actions 在 main、dev/** 和 PR 运行统一检查。普通随机 debug 包只用于内部构建；只有匹配保留密钥的 Secret 可生成并发布预览工件。离线 inspector 工件不是登录/文件客户端。构建报告保留在 Actions。

新增依赖后运行 `python3 tools/refresh-notices.py`，审阅实际许可；证书集合另保留 MPL-2.0。独立测试、编译、Lint 和 CI 不等于手机布局、真实账户或中国网络已通过；交付前对照 [验收清单](ACCEPTANCE.md)。
