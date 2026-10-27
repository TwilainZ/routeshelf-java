# GDirect Lite Android

受 [mizorewww/gdirect](https://github.com/mizorewww/gdirect) 的功能与使用思路启发，基于现有 Java Android 原型继续开发的 Google Drive 客户端。应用代码独立实现，没有移植该项目的源码、aria2 补丁或原生二进制；非 Google 或该项目作者的官方应用。

## 当前版本：0.6.0-drive-preview 开发预览

当前按 [阶段验收与统一交付](docs/RELEASE_GATE.md) 完善功能；中间提交不反复发安装包或要求 Google 配置。预览身份和证书固定，普通更新无需重新注册客户端。

Android 10（API 29）及以上，无需 Root。

0.6.0 补齐 Google 前端身份模式、专用根证书、多路线缓存和安全内容跳转，并将主界面改为更接近 Google Drive 的文件浏览。包名、证书与上一测试版一致，无需再次注册 Google 客户端。

- **文件界面**：默认打开云盘，顶部固定搜索，底部首页（最近修改）/星标/共享/文件导航；类型图标、文件大小和修改时间、右侧更多菜单、右下角新建/上传。回收站和共享云盘通过文件范围标题选择；公开链接和传输入口在菜单中。支持日夜主题、系统栏/键盘 insets 和可增长的触摸控件。尚无本版真机截图，不把静态布局审查当作设备验收。
- **自动连接**：先验证 DNS/历史候选，失败才扩大到最多 512 个官方范围内的 TCP 候选，单连接 1.2 秒；采用正常 SNI、Google 前端身份（TLS 与证书名称 g.cn，真实 HTTP Host）及无 SNI。仅信任内置四张 Google GTS 根证书，启动与 APK 检查均核对证书包；最多缓存八条已验证的 IP/TLS 组合 24 小时，失败后使用备用组合。普通首次检测预算 45 秒，成功的常见路径不等待完整 TCP 扫描。
- **连接选择**：默认仅 Google 前端；可在设置明确允许已知第三方中转 183.56.143.147，界面说明归属及流量信息可见性，始终保留 Google TLS 验证。它是额外候选，不保证可用，不声称境内物理路径。官方地址列表不可达时，只有主动允许中转的模式可尝试这个固定候选；不会开放任意中转。详见 [连接说明](docs/DIRECT_NETWORK.md) 与 [根证书来源](docs/TRUST_ROOTS.md)。
- **公开链接下载**：保留原型的链接解析、前台下载和系统 Downloads 集成。公开文件无需登录。
- **安全续传**：仅在服务器提供强 ETag 时进行分块/续传，使用 `If-Range` 并核对 Content-Range 和文件版本；没有可靠版本标识时改用单流，避免把不同版本拼接起来。
- **Google 授权**：通过 Google 官方 Android `AuthorizationClient` 默认请求只读权限。文件管理功能在用户明确选择后另行请求 Drive 管理权限；只读连接不会因 SDK 缓存了更宽权限就自动开启写入。Android SDK 管理令牌获取；未实现桌面版 0.9.0 的自管刷新令牌直连。令牌只在进程内存中使用，不保存刷新令牌。
- **私人文件浏览**：My Drive 根目录、文件夹、当前文件夹搜索和分页；支持断开本机连接及撤销 Google 授权。
- **浏览恢复**：刷新失败保留同一上下文的已加载内容，提供重试；切换账号或搜索立即清除旧列表。分页去重并防止重复分页循环，搜索可一键清空，传输失败提示保留在云盘页。
- **文件库与排序**：所有文件、最近修改、已加星标、与我共享、回收站和共享云端硬盘；支持范围内搜索、分页和排序，支持快捷方式及资源密钥，查看文件详情。
- **文件管理**：新建文件夹、重命名、星标、移动、移入回收站和恢复。永久删除仅在用户确认、文件仍处于回收站且具有可靠版本标识时执行，以条件请求防止删除已改变的文件。
- **上传**：系统文件选择器、当前目录上传、前台进度和取消；512 KiB 分块，在响应丢失后查询已接收偏移再继续，完成后核对大小与可用 MD5。临时副本在应用私有目录，上传会话不跨进程恢复，同名上传默认创建新文件而非覆盖。
- **预览**：PDF 分页、常见图片、文本/JSON、受设备支持的常见音视频；私有缓存通过直连读取，使用 Android 本地组件，不把授权 URL 交给网页。文本限 512 KiB、PDF/图片限 64 MiB、音视频限 256 MiB；更大文件可下载后打开。
- **共享**：复制链接不改变权限；可添加查看者或编辑者、明确确认后允许链接查看、查看权限列表并移除可移除授权。不会自动移除文件所有者或继承权限。
- **Google 文档导出**：文档、幻灯片和绘图导出 PDF，表格导出 XLSX；遵守下载权限和 10 MiB 导出限制，接收完成后保存到 Downloads。其他 Google 原生类型和其他导出格式尚不支持。
- **私人普通文件下载**：有强 ETag、Range 和 MD5 时使用安全并发分块，否则单流；支持前台进度、取消；在 Drive 提供校验值时核对 MD5，并核对文件大小后保存到 `下载/GDirect Lite`。私人请求不会使用手填 IP；只有文件内容 GET 可跳转至严格检查的 Google 内容域名，跳转后不发送访问令牌、资源密钥、Cookie 或 Referer。元数据和写操作不跟随跳转。

**验证范围：** 维护者报告原 v0.1.0 公开下载成功，后续安装的 v0.2.0 在核对实际签名并修正 Android OAuth 客户端后登录成功。Cloud 已配置，无需重复初始化。当前开发版本的构建和本地 TLS/协议测试可以复现；本版的免代理直连、私人文件下载及中国运营商网络尚未完成设备验收。历史手机结果不能替代本版本测试。

## 构建与测试

Android Studio 打开仓库根目录即可。命令行需要 JDK 17、Android SDK API 35 和 Build Tools 34.0.0 / 35.0.0；设置 `ANDROID_HOME` 或本机未跟踪的 `local.properties`。首次构建会联网下载依赖。

```sh
bash tests/run-java-checks.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2
```

Gradle Wrapper 固定 8.9，并校验分发包 SHA-256。Android Gradle Plugin 固定 8.7.3。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，测试与 Lint 报告位于 `app/build/reports/`。

[GitHub Actions](.github/workflows/build-apk.yml) 对 `main` / `dev/**` 推送和 PR 直接构建源码，执行检查后再直接检验 APK 内的版本、提交、诊断资源及签名，固定测试签名可用时才上传对应 APK 工件；临时随机签名的主 APK 仅用于内部构建检查，不再作为升级包发布。可在手机页面顶部查看版本/构建，并从 Drive 页菜单复制实际安装包的签名与授权诊断。它帮助区分安装包和错误阶段，不表示真实 Google 登录已修好。测试签名的持久配置与当前限制见 [配置文档](docs/GOOGLE_SETUP.md)。

## Google 登录配置

已有 v0.2.0 用户测试新版时，使用应用名为 **GDirect Lite 测试版**、包名 `dev.gdirect.lite.preview` 的并存包，保留旧版。它使用固定测试证书，打包器会核对 `config/preview-signing.json`，缺少原密钥时拒绝生成可交付 APK。测试版的 Google 登录需在同一 Cloud 项目为新包名及其签名新增 Android OAuth 客户端；原客户端保留，无需重新启用 Drive API。详见 [并存测试版](docs/GOOGLE_SETUP.md#并存测试版解决安装签名冲突)。

如果手机反复出现相同错误且无法确认安装包，可使用 CI 的独立 `GDirectLite-inspector-运行编号` 工件。它安装为“GDirect 安装包检查”（包名 `dev.gdirect.lite.probe`），与原应用并存，读取原应用实际版本、签名及错误资源标记；不进行登录、不访问私有数据、没有 INTERNET 权限。无需修改原应用或为检查工具配置 OAuth。本地可执行 `./gradlew :inspector:assembleDebug :inspector:lintDebug`，详情见配置文档中的离线检查章节。

按 [GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md) 启用 Drive API、设置同意屏幕和测试用户，注册包名 `dev.gdirect.lite` 与实际 APK 签名 SHA-1 的 **Android OAuth 客户端**。需要带 Google Play 服务的设备。

应用不需要嵌入 Desktop/Web 客户端密钥或账户密码。`drive.readonly` 是 restricted scope；公开发布前需按 Google 当时政策完成适用的权限验证、隐私政策和安全评估。

## 使用

公开文件：粘贴 Google Drive 单文件链接后开始下载，默认自动检测直连线路；可选系统网络或高级手填公开下载 IP。可选 1 / 4 / 8 条连接，在服务器提供稳定版本标识时启用分块。取消后重试只复用仍匹配该文件版本的完整分块。

私人文件：打开“我的 Google Drive”，点击连接，完成 Google 的账号选择/授权；进入文件夹或搜索，点击普通文件的“下载到设备”。返回下载页可查看进度、取消和打开已完成文件。菜单提供撤销授权及许可证声明。

## 当前限制与下一步

- 私人下载暂不跨会话续传；断开连接会使旧请求失效。进程退出后需重新点击连接，由官方 SDK 重新获取授权令牌。
- 尚未实现传输队列、离线文件同步、跨进程上传续传、协同文档编辑或挂载；每次只传输一个文件。同名上传不自动替换旧文件，预览和媒体格式受大小及设备解码能力限制。不能宣称与官方 Drive 完全等价。
- Google 公开链接的确认页、resource key、下载配额与网络环境并不统一；解析不能保证覆盖所有情形。保持严格 TLS 验证，不将 IP 测试视为 VPN。
- 数据通道支持标准 TLS、无 SNI 和固定 Google 前端身份；各模式验证对应的 Google TLS 名称，暂不提供任意自定义 SNI。登录、重新获取授权及远端撤销仍由 Google 服务通过系统网络完成，身份服务不可达时仍需解决其连通性。
- Android 对前台数据同步服务有启动及运行时长限制；大文件建议保持应用前台。Android 13+ 会请求通知权限。
- 下载临时文件位于应用私有存储/缓存，发布到系统下载目录时可能需要接近两倍文件大小的空间。已保存的下载不会因断开 Google 授权而删除。

## 工程与来源

- `app/`：正式 Android 源码及 JVM 单元测试。
- `tests/`：不需要 Android 设备的独立 Java 解析检查。
- `archive/`：原始 v0.1.0 ZIP，保留字节及原 GPL 许可，仅供历史参考，不参与构建。
- [许可与来源审查](docs/LICENSING.md)：维护者授权改用 Apache-2.0 的记录。
- [第三方声明](THIRD_PARTY_NOTICES.md)：Google SDK、AndroidX、Kotlin 及工具/测试依赖的许可；新增依赖后运行 `python3 tools/refresh-notices.py` 并复核。

当前应用自有源码采用 [Apache License 2.0](LICENSE)。第三方组件仍保留其自己的条款，Google SDK 不是本项目重新授权的 Apache 代码。功能灵感不意味着代码移植，也不影响维护者决定自主实现的后续方向。
