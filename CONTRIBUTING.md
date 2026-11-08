# 参与贡献

如果遇到问题，先查阅 [安装与配置](docs/INSTALL.md) 和 [日志说明](docs/DIAGNOSTICS.md)，再提交 Issue。说明应用版本、系统、复现步骤和预期行为。涉及越权或敏感数据时，请走 [安全报告流程](SECURITY.md)。

欢迎从 fork 新建分支，并向 `main` 提交 Pull Request。提交前阅读 [仓库开发约定](AGENTS.md)，运行与改动相关的 [Android 检查](docs/DEVELOPMENT.md) 。在 PR 中说明改动后的行为、实际验证结果及仍待验证的部分。网络功能请区分回环测试、真实账号和实际网络结果。

贡献以 [GPL-3.0-only](LICENSE) 提交。Android 的 Google Play 服务客户端库使用 [有限链接例外](LICENSES/Google-Play-Services-exception.txt)，涉及 Android 应用的贡献须能按该许可安排分发。引入外部代码或依赖前，请核对来源、许可兼容性与保留声明的要求。

构建输出留在本地。仓库、PR 和 Issue 只接受脱敏材料。用于已有用户升级的签名身份由维护者按 [交付指南](docs/RELEASE_GATE.md) 管理。
