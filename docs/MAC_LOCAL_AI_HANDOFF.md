# 给本机 Mac AI 的任务说明

你运行在我自己的 Mac 上，可以使用本机终端和 Xcode。请实际完成以下工作，不要停留在建议或计划。

仓库：https://github.com/qingzhuernest/gdirect-lite-android ，使用最新 main。首先阅读最新 AGENTS.md、apple/README.md、apple/DESIGN.md。保留已有本地改动，不强制重置或改写 Git 历史。

目标：主要完善可复用 Swift 核心，并交付能在我 Mac 上运行的原生 macOS 应用。所有实现保持独立，禁止复制、翻译或导入 mizorewww/gdirect 的 GPL 实现、补丁或种子资产。保留现有许可归属和 GTS 证书许可。

当前已有 GDirectCore、GDirectApple、GDirectMac 三个模块；包含只读 Drive API、PKCE 授权协议、钥匙串、Google 前端选线、下载/有条件续传和文件校验，以及薄 SwiftUI 界面。Linux 的 31 项核心测试及 6 项签名身份选择测试通过；这不是 Mac 编译证据。首次 Mac 原生编译发现的多变量 @Published 声明已修正，完整 Mac 编译、测试和打包仍需本机验证。当前没有独立 .xcodeproj。

请按以下顺序完成：

1. 检查 Xcode 和 Swift 版本，在真实 macOS 执行 `bash apple/tools/check.sh`。定位并修复编译、测试失败或长时间停滞，保留有效测试，不通过删除测试或放宽 TLS 校验掩盖问题。
2. 补充可直接打开的 Xcode macOS App 工程，引用本地 GDirectCore/GDirectApple，复用现有 SwiftUI 源码。最低 macOS 13，兼容 Apple Silicon 和 Intel。保持 bundle ID `dev.gdirect.lite.mac`，正确包含资源、GTS 根和自有图标；从实际构建后的 App 验证资源读取。原生侧栏、表格、工具栏、设置和传输页保持清晰，优先系统组件。
3. 在我的 Mac 上实际运行 App，检查首次配置、浏览、搜索、传输、取消、连接模式和设置。没有真实 Google 授权时先使用明确标注的 `--ui-smoke` 示例；不能把示例截图当成账号验收。Google 配置使用一次性的桌面 OAuth 客户端，刷新凭据存本机钥匙串，不要要求每次更新重新注册。
4. 完成签名与打包，优先复用本机 Keychain 中已有的有效签名证书和私钥。阅读 `apple/tools/package-macos.sh`、`select-signing-identity.py`：auto 优先 Developer ID Application，其次 Apple Development；多个身份时明确选择我的团队和指纹。不要随意换身份，也不要生成临时私钥冒充正式签名。若需登录 Apple ID 或选择团队，让我在 Xcode/系统界面完成，不索取密码、验证码或证书密码。
5. 有 Developer ID 时使用 hardened runtime 和安全时间戳。公证凭据由我在本机通过 `notarytool store-credentials` 交互保存到 Keychain，再使用 `GDIRECT_MAC_SIGNING=developer-id GDIRECT_MAC_NOTARY_PROFILE=GDirectLite bash apple/tools/package-macos.sh`，检查 Apple 接受状态、stapler 和 spctl。没有合适开发者资格时先完成本机开发运行，并明确说明实际签名类型；ad-hoc、Apple Development、Developer ID 与已公证分发必须区分。
6. 私钥留在我的本机钥匙串，不写入源码、聊天、日志、ZIP/DMG、GitHub 或 Actions。不要创建/导出新的 .p12 来绕过问题。若 Xcode 自动管理签名确需新建身份，先确认它属于我的团队且复用现有身份不可行，保留在 Keychain 并说明由我进行安全备份。不要承诺已经有外部备份。Android 包名、OAuth 客户端和原有签名材料不改。
7. 从干净提交生成通用 .app、ZIP、DMG、BUILD-IDENTITY.json、SHA256SUMS，检查版本/提交、双架构、codesign、安装后启动和资源。需要公证的产物必须真正通过公证验证。不要关闭 Gatekeeper 或关闭证书校验来通过测试。
8. 将完成且实际验证的源码、工程、脚本与清晰文档提交并正常推送到 GitHub main，保留历史；构建产物保持忽略，凭据不进入 Git。交付可点击的安装包路径，说明实际测试、签名类型、版本、提交和剩余需要我亲自操作的事项。发现错误继续修复，不要把“可以运行”当成“已经运行成功”。
