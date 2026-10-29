<img src="design/icon-preview.png" width="88" height="88" alt="GDirect Lite 应用图标">

# GDirect Lite Android

独立开发的 Android Google Drive 客户端：浏览、预览、管理和传输文件，并自动寻找当前网络可用的数据连接。界面采用熟悉的 Material 搜索、底部导航和文件操作。

**当前：0.7.0-settings-preview · Android 10+ · Java · 开发预览。** 非 Google 官方应用。功能思路受 mizorewww/gdirect 启发，未移植其源码、aria2 补丁、种子或界面资产。

[文档导航](docs/README.md) · [开发构建](docs/DEVELOPMENT.md) · [Google 配置](docs/GOOGLE_SETUP.md) · [验收清单](docs/ACCEPTANCE.md) · [更新记录](CHANGELOG.md)

## 可以做什么

- **文件浏览**：文件夹、搜索、分页和排序；最近修改、星标、与我共享、回收站和共享云端硬盘；快捷方式与资源密钥。
- **预览与导出**：PDF、图片、文本和受设备支持的音视频；Google 文档/幻灯片/绘图导出 PDF，表格导出 XLSX。私有缓存和本地预览，不把授权 URL 交给网页。
- **文件管理**：新建文件夹、重命名、星标、移动、回收站恢复及有条件的永久删除；共享链接、查看/添加/移除访问权限。默认只读，需要管理时明确申请权限。
- **上传下载**：公开单文件链接无需登录；私人传输、前台进度、取消、大小/可用 MD5 校验及 Downloads 保存。强 ETag/Range 条件满足时安全分块续传；上传按服务端确认偏移恢复当前会话。
- **自动连接**：官方 IPv4 范围优先、内置公开报告候选、DNS/历史候选、按需扩大的移动端探测、备用 IP/TLS 组合。支持正常 SNI、无 SNI 和固定 g.cn 前端身份，仅信任 Google GTS 根；已知第三方中转只在明确允许后作为候选。

“⋮ → 设置 → 连接”中的“查看内置 Google IP 候选”显示地址、日期和来源，无需手工找 IP。

顶部搜索、底部“首页/星标/共享/文件”、类型图标与右下角“新建”组成主流程。文件范围标题可选择回收站等范围；菜单只保留刷新、排序、公开链接下载、传输和设置；账户权限、连接、下载偏好、隐私及诊断统一放在设置页。支持日夜主题及 Android 自适应/主题启动器图标。

## 安装与现有测试版更新

当前尚无公开稳定 Release。不要将 Actions 的离线检查器或随机主 debug 包当作可升级客户端。可交付预览必须通过固定签名打包器验证，并包含 APK-IDENTITY.json 和校验值。

现有测试版身份为 `dev.gdirect.lite.preview`，证书固定在 [preview-signing.json](config/preview-signing.json)。本次版本码 8，可覆盖同签名测试版；与旧主包 `dev.gdirect.lite` 并存。维护者已报告完成 Google 配置，普通更新无需再注册 OAuth，也无需删除旧主版。新开发者的一次性配置见 [Google 设置](docs/GOOGLE_SETUP.md)。

私钥不进入仓库、APK 或交付 ZIP。缺少原密钥时拒绝交付，不自动生成替代签名。CI 只有匹配的预览密钥 Secret 可用时才发布预览工件；目前未确认该项配置，没有密钥仍可完成源码检查。

## 构建与验证

需要 JDK 17、SDK Platform 35、Build Tools 34/35；使用项目固定 Gradle Wrapper，无需另装 Gradle。

```sh
# 配置 ANDROID_HOME，或在未跟踪的 local.properties 中设置 sdk.dir
bash tools/check.sh
```

统一入口执行 Java 检查、debug/preview JVM 测试、编译和 Android Lint；不需要预览私钥。[GitHub Actions](.github/workflows/build-apk.yml) 使用同一入口。只有保留签名可用时才执行 [预览打包器](tools/build-preview-apk.sh)，核对实际包名、版本、提交、签名和根证书包。

0.7.0 本地验证为每变体 115 项 JVM 测试、11 项独立检查及编译/Lint；真实回环 TLS 覆盖 SNI/Host、证书、凭据跳转和回退。**本版手机布局、真实账号和中国运营商网络仍未验收**，不以编译代替使用效果。

## 目前的边界

- 登录、重新获取令牌和撤销依赖 Google Play 服务的系统网络；没有实现桌面版自管刷新令牌直连。数据可达不代表所有 Google 服务可达。
- 内置 6 个候选，来自 2026-10-26 上海联通公开报告中的 3 个 Google /24 网段；具体地址为独立选取并实时复核，不是逐 IP 的国内可用保证。官方列表暂不可达时只在这些有限范围内启动验证，仍会重试官方更新；目前没有完整快照。候选扫描规模小于桌面版，不保证所有网络成功。允许第三方中转前会说明运营归属和流量信息可见性；详情见 [连接设计](docs/DIRECT_NETWORK.md)。
- 每次传输一个文件；尚无队列、离线同步、跨进程私人传输恢复、协作编辑或挂载。不能宣称与官方 Drive 功能完全等价。
- 文本预览限 512 KiB，图片/PDF 限 64 MiB，音视频限 256 MiB；Google 文档导出限 10 MiB。媒体解码受设备支持影响。
- 上传同名文件默认新建，不覆盖；Android 前台服务和存储空间限制仍适用。私人内容跳转严格检查目标并剥离凭据，元数据/写操作不自动跟随跳转或盲目重放。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| app/ | Android 客户端及 JVM 测试 |
| inspector/ | 无联网权限的独立旧主包检查器 |
| tests/ · tools/ | 独立检查、统一验证、签名打包与许可清单 |
| config/ | 公开签名指纹和 Google 根证书摘要 |
| docs/ · design/ | 使用/开发文档、验收、历史记录与图标设计 |
| archive/ | 原始独立 Java 原型 ZIP，不参与构建 |

## 许可与贡献

自有应用源码和图标采用 [Apache-2.0](LICENSE)。GTS 公开证书集合子集保留 MPL-2.0，Google SDK 和其他组件保留各自条款；历史原型 ZIP 保留原 GPL 许可。参见 [归属审查](docs/LICENSING.md)、[第三方声明](THIRD_PARTY_NOTICES.md) 和 [信任根来源](docs/TRUST_ROOTS.md)。

开发约束见 [AGENTS.md](AGENTS.md) 与 [贡献指南](CONTRIBUTING.md)。安全问题请先阅读 [SECURITY.md](SECURITY.md)，不要提交令牌、私人链接或签名私钥。
