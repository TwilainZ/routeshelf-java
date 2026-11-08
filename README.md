<img src="docs/design/icon-preview.png" width="88" height="88" alt="GDirect Lite 应用图标">

# GDirect Lite Android

**Swift 核心与 macOS 开发已加入 [apple/](apple/README.md)**：可复用库、Apple TLS/钥匙串适配、原生 SwiftUI 客户端及通用 Mac 打包工作流。Android 当前版本与原固定签名保持；Apple 编译结果和交付状态单独记录，不能用 Linux 核心测试代替。

独立开发的 Android Google Drive 客户端：浏览、预览、管理和传输文件，并自动寻找当前网络可用的数据连接。界面采用熟悉的 Material 搜索、底部导航和文件操作。

**当前：0.8.4-navigation-preview · Android 10+ · Java · 开发预览。** 非 Google 官方应用。功能思路受 mizorewww/gdirect 启发，未移植其源码、aria2 补丁、种子或界面资产。

[文档导航](docs/README.md) · [开发构建](docs/DEVELOPMENT.md) · [Google 配置](docs/GOOGLE_SETUP.md) · [验收清单](docs/ACCEPTANCE.md) · [更新记录](CHANGELOG.md)

`main` 保存最新通过源码验证的版本；旧版本可在 Git 提交历史中查看，无需切换到开发分支获取最新代码。

## 可以做什么

- **连接状态可见**：点击云盘顶部状态入口或“设置 → 连接状态与检测”，分别查看账户、云盘服务、文件下载；检测进度、耗时、重试/取消、最近检查和设备 VPN 状态实时更新。授权、线路验证与实际下载分开显示。
- **保持连接**：成功连接一次后记住上次账户与权限选择，重启后通过 Google 官方授权服务自动恢复；只有 Google 要求重新确认时才需操作。主动断开或撤销会忘记本机连接。令牌仍只保留在内存。
- **文件浏览**：文件夹、搜索、分页和排序；最近修改、星标、与我共享、回收站和共享云端硬盘；快捷方式与资源密钥。
- **预览与导出**：PDF、图片、文本和受设备支持的音视频；Google 文档/幻灯片/绘图导出 PDF，表格导出 XLSX。私有缓存和本地预览，不把授权 URL 交给网页。
- **文件管理**：新建文件夹、重命名、星标、移动、回收站恢复及有条件的永久删除；共享链接、查看/添加/移除访问权限。默认只读，需要管理时明确申请权限。
- **上传下载**：公开单文件链接无需登录；私人传输、前台进度、取消、大小/可用 MD5 校验及 Downloads 保存。强 ETag/Range 条件满足时安全分块续传；上传按服务端确认偏移恢复当前会话。
- **自动连接**：官方 IPv4 范围优先、内置公开报告候选、DNS/历史候选、按需扩大的移动端探测、备用 IP/TLS 组合。支持正常 SNI、无 SNI 和固定 g.cn 前端身份，仅信任 Google GTS 根；已知第三方中转只在明确允许后作为候选。

“⋮ → 设置 → 连接”中的“内置 Google 候选”显示地址、日期和来源，无需手工找 IP。 已同意中转的用户可选择“连接方式 → 允许已知中转”：先验证旧版使用的固定中转，成功不等待地址列表/DNS，失败再寻找 Google 前端；“仅 Google”仍完全排除中转。

0.7.3 修正纯 Google 扫描截断、TCP 应答挤占验证，以及缓存线路失效后的 TLS 身份恢复。设置采用 AndroidX 官方列表与 Material 单选对话框；检测中切换方式会先停止旧检测，再保存新选择。

0.8.1 修复目录加载成功后反复刷新，以及取消读取误淘汰健康线路的问题。目录元数据 GET 每次 HTTP 尝试增加 12 秒整体时限，不限制文件内容下载/导出或上传。

出现连接慢或失败时，进入 **设置 → 连接日志与反馈**，复现后刷新，复制日志或保存 TXT 发来反馈。记录阶段耗时、选线和错误类型；日志只在本机保留最近 512 条，不含令牌、账户、文件名或私人链接。详见 [日志与反馈](docs/DIAGNOSTICS.md)。

顶部搜索、底部“首页/文件”、类型图标与右下角“新建”组成主流程。文件范围标题可选择回收站等范围；“查看传输”和通知直接进入任务详情，下载完成可打开文件，不再跳到链接输入表单。主菜单按浏览、传输、设置分组；文件更多菜单直接提供下载、重命名、移动等操作，回收站单独提供恢复/永久删除；账户权限、连接、下载偏好、隐私及诊断统一放在设置页。按钮、导航、新建和搜索使用统一的 Material 颜色角色：蓝色主操作、浅蓝选中态及中性日夜表面，次要操作采用文字按钮。支持日夜主题及 Android 自适应/主题启动器图标。

## 安装与现有测试版更新

当前尚无公开稳定 Release。不要将 Actions 的离线检查器或随机主 debug 包当作可升级客户端。可交付预览必须通过固定签名打包器验证，并包含 APK-IDENTITY.json 和校验值。

现有测试版身份为 `dev.gdirect.lite.preview`，证书固定在 [preview-signing.json](config/preview-signing.json)。本次版本码 19，可覆盖同签名测试版；与旧主包 `dev.gdirect.lite` 并存。维护者已报告完成 Google 配置，普通更新无需再注册 OAuth，也无需删除旧主版。新开发者的一次性配置见 [Google 设置](docs/GOOGLE_SETUP.md)。

私钥不进入仓库、APK 或交付 ZIP。缺少原密钥时拒绝交付，不自动生成替代签名。CI 只有匹配的预览密钥 Secret 可用时才发布预览工件；目前未确认该项配置，没有密钥仍可完成源码检查。

## 构建与验证

需要 JDK 17、SDK Platform 35、Build Tools 34/35；使用项目固定 Gradle Wrapper，无需另装 Gradle。

```sh
# 配置 ANDROID_HOME，或在未跟踪的 local.properties 中设置 sdk.dir
bash tools/check.sh
```

统一入口执行 Java 检查、debug/preview JVM 测试、编译和 Android Lint；不需要预览私钥。[GitHub Actions](.github/workflows/build-apk.yml) 使用同一入口。只有保留签名可用时才执行 [预览打包器](tools/build-preview-apk.sh)，核对实际包名、版本、提交、签名和根证书包。

0.8.4 本地验证为每变体 201 项 JVM 测试、11 项独立检查及编译/Lint；真实回环 TLS 覆盖 SNI/Host、证书、凭据跳转和回退。**本版手机布局、真实账号和中国运营商网络仍未验收**，不以编译代替使用效果。

## 目前的边界

- 登录、重新获取令牌和撤销依赖 Google Play 服务的系统网络；没有实现桌面版自管刷新令牌直连。数据可达不代表所有 Google 服务可达。
- 内置 6 个候选，来自 2026-10-26 上海联通公开报告中的 3 个 Google /24 网段；具体地址为独立选取并实时复核，不是逐 IP 的国内可用保证。官方列表暂不可达时只在这些有限范围内启动验证，仍会重试官方更新；目前没有完整快照。候选扫描规模小于桌面版，不保证所有网络成功。允许第三方中转前会说明运营归属和流量信息可见性；详情见 [连接设计](docs/DIRECT_NETWORK.md)。
- 每次传输一个文件；尚无队列、离线同步、跨进程私人传输恢复、协作编辑或挂载。不能宣称与官方 Drive 功能完全等价。
- 文本预览限 512 KiB，图片/PDF 限 64 MiB，音视频限 256 MiB；Google 文档导出限 10 MiB。媒体解码受设备支持影响。
- 上传同名文件默认新建，不覆盖；Android 前台服务和存储空间限制仍适用。私人内容跳转严格检查目标并剥离凭据，元数据/写操作不自动跟随跳转或盲目重放。

## 仓库结构与关键代码

| 位置 | 用途 |
| --- | --- |
| app/src/main/java/dev/gdirect/lite/ | Android 入口及按职责分包的客户端代码 |
| app/src/test/java/dev/gdirect/lite/ | 与实现分包对应的协议、TLS、会话和日志测试 |
| app/src/main/res/ · assets/ · resources/ | 界面与图标、隐私/依赖声明、候选范围与根证书 |
| tools/ | 构建/验证工具、独立检查及旧主包离线检查器 |
| config/ | 公开签名指纹与信任根元数据，不存私钥 |
| docs/ | 使用/开发文档；design/ 为图标资料，history/ 为历史证据和原型 ZIP |
| gradle/ · Gradle Wrapper | 固定构建工具链 |

连接入口是 [GoogleNetwork](app/src/main/java/dev/gdirect/lite/GoogleNetwork.java)，选线在 [network/DirectRoutes](app/src/main/java/dev/gdirect/lite/network/DirectRoutes.java)，TLS 与 HTTP 在 [network/GoogleHttp](app/src/main/java/dev/gdirect/lite/network/GoogleHttp.java)。完整职责和阅读顺序见 [关键代码导航](docs/ARCHITECTURE.md)。

## 许可与贡献

自有应用源码和图标采用 [Apache-2.0](LICENSE)。GTS 公开证书集合子集保留 MPL-2.0，Google SDK 和其他组件保留各自条款；历史原型 ZIP 保留原 GPL 许可。参见 [归属审查](docs/LICENSING.md)、[第三方声明](THIRD_PARTY_NOTICES.md) 和 [信任根来源](docs/TRUST_ROOTS.md)。

开发约束见 [AGENTS.md](AGENTS.md) 与 [贡献指南](CONTRIBUTING.md)。安全问题请先阅读 [SECURITY.md](SECURITY.md)，不要提交令牌、私人链接或签名私钥。
