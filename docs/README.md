# 文档导航

| 目的 | 文档 |
| --- | --- |
| Swift 核心、macOS 源码、构建与交付边界 | [Apple 工程](../apple/README.md) |
| 功能、版本与主要限制 | [项目首页](../README.md) · [更新记录](../CHANGELOG.md) |
| 核心职责、调用链和源码入口 | [关键代码导航](ARCHITECTURE.md) |
| 本地构建、测试、CI、签名与目录 | [开发环境](DEVELOPMENT.md) |
| Google 一次性配置与授权排障 | [Google 设置](GOOGLE_SETUP.md) |
| 自动连接、TLS 身份、中转与凭据边界 | [连接设计](DIRECT_NETWORK.md) |
| 连接慢/失败的日志记录、复制与导出 | [日志与反馈](DIAGNOSTICS.md) |
| Google 根证书来源、指纹和许可 | [信任根](TRUST_ROOTS.md) |
| 设备/账号/网络验收 | [验收清单](ACCEPTANCE.md) · [交付门槛](RELEASE_GATE.md) |
| 菜单层级、返回与本轮交互审查 | [流程审查](UI_FLOW_REVIEW.md) |
| 产品与 UI | [产品约束](../PRODUCT.md) · [设计规则](../DESIGN.md) · [UI 检查](UI_NOTES.md) |
| 代码归属与第三方许可 | [归属审查](LICENSING.md) · [依赖声明](../THIRD_PARTY_NOTICES.md) |
| 贡献与安全问题 | [贡献](../CONTRIBUTING.md) · [安全](../SECURITY.md) |

`history/` 只保存历史审查证据，不代表当前功能或配置。源码 ZIP 在 docs/history/prototype/，不参与构建。当前版本以 app/build.gradle 为准；真实设备完成情况以验收记录为准。
