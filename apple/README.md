# GDirect Lite · Swift 核心与 macOS

独立 Swift Package，应用代码 Apache-2.0。延续本仓库自己的 API/网络设计；没有导入或翻译参考项目的 GPL 实现。Android 工程和固定签名保持不变。

## 结构与当前能力

| 模块 | 内容 |
| --- | --- |
| GDirectCore | Drive 只读 API、分页与搜索、OAuth PKCE/回调/刷新协议、增量 HTTP/1.1、Google 地址过滤与选线、流式下载/有条件续传/文件完整性与安全保存 |
| GDirectApple | Network.framework 固定 IP 与三种 TLS 身份、GTS-only 验证、系统模式、钥匙串、网络变化失效、macOS 浏览器授权呈现 |
| GDirectMac | SwiftUI/AppKit 侧栏、文件表格、传输详情、连接检查和账户设置；系统保存位置选择器与 Finder 打开 |
| Tests | 无公网核心测试，以及仅在 Mac 执行的真实回环 TLS/证书/MD5 测试 |

当前 Mac 版本 0.1.0，最低 macOS 13；打包目标为 Apple Silicon + Intel 通用应用。共享库声明 iOS 16+，为后续复用预留；本轮不包含 iOS App 或 iOS 授权入口。桌面 loopback OAuth 不得直接拿去替代 Google 的 iOS 授权要求。

已实现只读浏览、搜索、分页、普通文件下载及 Google 文档导出（PDF/XLSX）；公开单文件链接有独立入口。下载使用临时文件，检查预期大小与可用 MD5，再发布到用户选择的目录；不覆盖已有文件/符号链接。强 ETag、有效 Content-Range 和一致总大小满足时，当前任务可重试续传；否则重新从零读取，不混合版本。Google 内容跳转剥离全部账户和资源密钥凭据。

尚无上传、文件修改、共享管理、队列、后台离线同步、跨进程任务恢复、原生文件预览、完整官方范围快照或连接池优化。界面“双击普通文件”目前会先让用户选择保存目录再下载，并非打开云端预览。

## Google 一次性配置

已有 Android OAuth 客户端继续保留，不改其包名/签名。Mac 的公开桌面客户端不使用 Android 签名 SHA-1。

1. 在自己已有的 Google Cloud 项目保留已启用的 Drive API 和实际测试账号。
2. 新建类型为“桌面应用”的 OAuth 客户端，下载 Google 提供的 JSON。
3. 在 Mac 应用的设置中“导入桌面 OAuth JSON”，再点击连接 Google，使用系统浏览器完成同意。
4. 应用默认只请求 drive.readonly。回调只监听随机端口的 127.0.0.1；检查 state、PKCE 和五分钟有效期。浏览器初次授权仍需要能访问 Google 登录页面。

JSON 中可能含有 Google 提供给公开桌面客户端的 client_secret；它是客户端配置，不是 Google 账号密码，也不是 Android/Apple 签名私钥。配置和刷新令牌仅存本机 Keychain，ThisDeviceOnly、不启用 iCloud 同步；访问令牌只在内存。不要把 JSON、令牌或签名材料上传聊天、仓库或构建日志。更新同一桌面客户端不需每次再创建 OAuth 客户端。

断开会清除本机刷新凭据、使旧会话失效，并保留客户端配置以便下次连接；它不替代 Google 账号安全设置中的撤销授权。尚未执行真实账号的 Mac 授权/下载验收。

## 网络行为

默认仅 Google 前端。候选来自本仓库已审查的六个引导地址，以及成功取得的 Google 官方 goog.json 减去 cloud.json 的有限随机补充；本轮最多 24 个 Google 候选、每批三条探测，三种 TLS 身份分别验证，有界发现。引导地址是公开报告网段内的候选，不保证个别 IP 在任何地区可用。

普通身份/无 SNI 仍验证真实逻辑主机名；固定 g.cn 身份验证 g.cn，同时 HTTP Host 保持真实 Google 服务名，使用 HTTP/1.1。自有传输包括系统模式均只信任打包且哈希固定的四个 GTS 根，不合并用户/系统 CA；浏览器页面遵循浏览器自己的验证。TLS 后、发送凭据前及读取中检查会话/网络有效性。原生路由失败可换线，取消、磁盘或内容校验错误不把健康线路标为失败；不重放 OAuth POST。

“允许已知中转”必须明确同意，183.56.143.147 会标为第三方。数据仍验证 Google TLS，但第三方能观察连接信息。固定端点/Google 前端也不代表绕过设备 VPN。服务匿名探测成功不等于真实文件下载完成。

## 构建与交付

共享核心可以在 Swift 6.0+ 的 Linux 上真实测试；Apple 框架/界面必须在 macOS + Xcode 编译。

```sh
swift test --package-path apple --jobs 3
# 或统一入口（在 Linux 会明确提示只覆盖可移植核心）
bash apple/tools/check.sh

# 仅 Mac：从干净提交构建通用 .app、ZIP、DMG 和身份/校验清单
bash apple/tools/package-macos.sh
```

[macOS 构建工作流](../.github/workflows/build-macos.yml)在 macos-15 实际运行核心和回环 TLS 测试，编译两种架构，验证签名/资源及安装目录中的启动自检，再上传工件。输出在 apple/build/distribution/完整提交/。每个工件包含 BUILD-IDENTITY.json 和 SHA256SUMS。编译结果以对应提交的 Actions 为准，Linux 成功不能代替 Mac 编译。

本轮为 **ad-hoc 开发预览签名，未 Apple 公证**；没有创建或丢弃永久 Apple 私钥。macOS 对从网络下载的预览可能要求用户在系统“隐私与安全性”中明确允许打开。正式公开分发需要所有者自己的 Developer ID 签名与公证，以获得稳定分发身份和钥匙串访问连续性；构建脚本没有关闭系统保护，也没有上传到 App Store。请不要将当前预览说成 Apple 已公证应用。

## 验证边界

本地 Linux 已执行共享核心测试，覆盖协议分帧、PKCE、安全跳转、续传/取消/防覆盖和选线状态。Mac 专有测试覆盖真实 TLS、主机名/根证书拒绝、发送前会话守卫及 MD5；需以 Mac 工作流的实际结果记录完成状态。真实账号、运营商网络、Mac 安装/钥匙串交互和 UI 触控仍需设备验收。

工作流尝试从真正运行的 Mac App 截图；--ui-smoke 明确显示示例数据并禁止网络/钥匙串操作，不能作为真实账号证据。没有图形会话时截图步骤可单独失败，不掩盖必需的编译/测试失败。源码审查已经修复发现的编译与生命周期问题；截图未取得之前不标记视觉验收通过。

证书许可见 Resources/gts-roots.LICENSE；其他归属见 [NOTICE](NOTICE)。
