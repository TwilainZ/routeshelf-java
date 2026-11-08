# 开发、验证与交付

## 环境

需要 JDK 17、Android SDK Platform 35、Build Tools 34.0.0 和 35.0.0。项目使用固定 Gradle 8.9 Wrapper（含分发 SHA-256 校验）和 Android Gradle Plugin 8.7.3，无需另外安装 Gradle。

Android Studio 打开仓库根目录，安装上述 SDK；或命令行配置 `ANDROID_HOME`。也可在不提交的 local.properties 中设置 sdk.dir。首次构建需要联网下载依赖；云环境应遵守自身网络和 CA 配置，不绕过代理策略。

```sh
bash tools/check.sh
```

这会执行 11 项独立 Java 检查、debug/preview JVM 测试、主 debug APK 构建、两变体 Lint，以及离线检查器构建/Lint。不需要预览私钥，不发布 APK，不改变 Google Cloud。测试报告在 app/build/reports/。当前 0.7.5 各变体 182 项 JVM 测试；测试数量会随开发变化。

## 源码目录

| 路径 | 用途 |
| --- | --- |
| app/src/main/java/dev/gdirect/lite/ | Android 入口；辅助类按 auth/network/drive/transfer/preview/diagnostics/ui 分包 |
| app/src/test/java/dev/gdirect/lite/ | 同职责分包的 JVM 测试 |
| app/src/main/res/ · assets/ · resources/ | 界面、声明、范围候选与证书资源 |
| tools/checks/ | 不需要 Android 设备或公网的独立 Java 检查与合成基准 |
| tools/legacy-inspector/ | 旧主包离线身份检查器，Gradle 项目名仍为 :inspector |
| tools/ | 统一检查、签名打包、APK 校验与声明生成 |
| config/ | 公开签名与信任根元数据 |
| docs/design/ · docs/history/ | 图标来源、历史记录与原始 ZIP |

客户端保持单一 Gradle 模块，内部以职责子包协作；后续按实际依赖再决定是否需要独立模块。

## 可安装的测试版

唯一现有预览身份为 dev.gdirect.lite.preview，证书见 config/preview-signing.json。普通源码检查不需要密钥；交付必须安全恢复同一保留密钥，设置 GDIRECT_PREVIEW_KEYSTORE_PATH，然后在已提交且干净的工作树执行：

```sh
bash tools/build-preview-apk.sh
```

打包器拒绝缺失/错误密钥，核对实际包名、版本、提交、签名和 GTS 根包，输出至 app/build/outputs/verified-preview/提交/。不要生成替代私钥来覆盖用户已有测试版。主包 dev.gdirect.lite 的旧签名是另一套身份，不能混用。现有维护者已报告完成 Google 配置，同一预览身份更新无需再登记客户端。

私钥不能提交、放入 ZIP、贴在 Issue 或聊天中。云环境当前保留了原密钥；外部备份和 CI Secret 尚未确认，不能宣称长期备份已完成。

## CI 与依赖

GitHub Actions 在 main、dev/** 和 PR 运行统一检查。普通随机 debug 包只用于内部构建；只有匹配保留密钥的 Secret 可生成并发布预览工件。旧检查器仅在手动工作流勾选 publish_legacy_inspector 时发布工件；它不是登录/文件客户端。构建报告保留在 Actions。

新增依赖后运行 `python3 tools/refresh-notices.py`，审阅实际许可；证书集合另保留 MPL-2.0。独立测试、编译、Lint 和 CI 不等于手机布局、真实账户或中国网络已通过；交付前对照 [验收清单](ACCEPTANCE.md)。

连接启动性能的本地合成对照：`bash tools/benchmark-relay-startup.sh 1820e38`（需 JDK）。对比相同的 1500ms 模拟元数据阻塞和探测回调，输出冷/热缓存耗时与来源调用次数；不连接公网、不是真实 TLS 或运营商测速。

日志测试覆盖固定字段脱敏、旧缓存重建、并发与限额、清除/恢复竞态、慢磁盘不阻塞记录，以及真实 TLS 中的令牌/URL/正文不进入日志。日志写盘是单线程延迟合并，不在请求或 UI 线程做文件 I/O。

按文件职责定位与调用顺序见 [关键代码导航](ARCHITECTURE.md)。Java 包路径和目录一致，Android 入口组件名称保持原值。
