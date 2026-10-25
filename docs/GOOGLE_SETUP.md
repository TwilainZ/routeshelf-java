# 配置 Google Drive 只读授权

本实现使用 Google 官方 Android `AuthorizationClient` 请求用户对 Drive 的授权。这是访问云盘数据的授权，不是本应用自行验证密码，也不建立应用自己的用户账户或服务器。

项目仍处于开发和测试阶段。尚未配置 Google Cloud 项目时可以编译、安装和运行公开文件下载；私人文件授权必须完成下面配置后在装有 Google Play 服务的 Android 设备上实测。

## Google Cloud 配置

1. 在你拥有的 Google Cloud 项目中启用 **Google Drive API**。
2. 在 **Google Auth Platform** 设置 Branding、Audience 和 Data Access。填写真实应用名称、支持邮箱等 Google 要求的资料；外部应用先保持 Testing，并加入实际测试账号。
3. 添加唯一需要的范围 `https://www.googleapis.com/auth/drive.readonly`。它允许读取用户的 Drive 文件和内容，是 **restricted scope**。`drive.file` 权限更窄，但不能满足浏览既有私人云盘文件的功能；本应用不申请可修改或删除文件的 `drive` 全权限。
4. 创建 **Android** 类型的 OAuth 客户端，包名为 `dev.gdirect.lite`，签名 SHA-1 必须对应你实际安装的 APK。不要创建 Desktop 客户端，也不需要把客户端密钥或 JSON 文件放进 Android 应用。
5. 安装 APK，打开“我的 Google Drive → 连接 Google Drive”，在 Google 提供的账号选择/同意页面中授权。应用检查是否实际获得只读范围，再加载文件列表。

应用无需嵌入 Web OAuth client ID、客户端密钥、账户密码或刷新令牌；它不请求 server auth code / offline access。Google SDK 根据 Android 包名和签名识别已注册的客户端。

## 查找 APK 的签名 SHA-1

在 JDK 17 / SDK / Gradle Wrapper 可用的仓库根目录执行：

```sh
./gradlew :app:signingReport
```

也可以检查准备安装的 APK，避免误用其他构建环境的签名：

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app-debug.apk
```

Android Studio、本地云环境和不同 GitHub Actions 运行生成的临时 debug keystore **可能不同**，因此 SHA-1 可能不同。本次普通 CI 不上传或固定签名私钥；不能假定给某个本地 APK 注册的 OAuth 客户端也适用于任意 CI APK。持续测试应使用维护者安全管理的稳定签名，再为其注册 Android 客户端。不要提交 keystore、签名口令或其他凭据。正式发布还需注册实际发行签名；使用 Play App Signing 时以 Play Console 的 app signing certificate 为准。

诊断版的 CI 工件名包含版本和运行编号，例如 `GDirectLite-0.2.1-diagnostic-RUN-ATTEMPT`。APK 文件名也包含版本、提交和运行编号，并附有 `APK-IDENTITY.json`、`signing-certificate.txt` 和 `SHA256SUMS`。这些文件只含公开构建/证书信息，不含签名私钥。旧版 `GDirectLite-debug-APK` 工件不具备新版标识，不能用文件名 `app-debug.apk` 或版本号 `0.2.0` 判断是否安装了修正版。

### 固定后续测试签名

当前集成没有读取/管理仓库 Actions Secrets 的权限，不能替维护者配置持久测试签名。工作流支持维护者提供 `GDIRECT_TEST_KEYSTORE_BASE64`：这是专用 Android 测试 keystore 的 Base64，使用标准 debug alias/password（`androiddebugkey` / `android`），只用于测试，不用于正式发布。私钥只应保存在安全目录及 GitHub 加密 Secret，不能提交到 Git 或发送到聊天。

维护者在自己的可信机器上准备好 JDK `keytool` 和具有该仓库 Secrets 写权限的 `gh`，选择仓库外的安全目录，可执行：

```sh
# 第一次只准备/复用专用测试签名，不上传、不覆盖已有签名。
bash tools/configure-test-signing.sh /secure/existing/parent
# 审阅其公开签名指纹并决定使用后，明确授权写入 GitHub Secret。
bash tools/configure-test-signing.sh /secure/existing/parent --publish-secret
```

后一个命令会设置/更新仓库的 `GDIRECT_TEST_KEYSTORE_BASE64`。保留同一个安全目录可复用同一把测试签名；不要用别的目录反复生成新签名。随后在 Google Cloud 为同一包名添加与该证书 SHA-1 匹配的 Android OAuth 客户端，并重新构建。不要删除旧客户端来猜测修复。

未配置该 Secret 时，CI 仍可完成测试和构建，但工件会明确记录 `temporary-debug` 并给出警告，不能假定它匹配任何现有 OAuth 客户端。配置后记录为 `pinned-test`。来自外部 fork 的 PR 不应获得此 Secret。

## 选完账号立即返回怎么办

账号选择器能打开不代表 Android OAuth 客户端已注册正确。若没有进入云盘只读权限同意页，先检查同一个 Cloud 项目中的 Android 客户端：包名是否为 `dev.gdirect.lite`，SHA-1 是否来自当前安装的 APK，以及所选账号是否已加入该项目的测试用户。单独启用 Drive API 不能替代这些 OAuth 配置。

旧预览版把所有非 `RESULT_OK` 的回调直接显示为“已取消授权”，会掩盖 SDK 返回的实际错误。修正版先解析返回的 Intent，只展示非敏感的数字状态码；没有结果时明确说明原因未知，不断言用户主动取消。状态 10 指向开发者/OAuth 配置问题；7/15 指向网络/超时；16 表示 SDK 报告授权未完成，并不能单凭该码确定 API 开关状态。

`0.2.1-diagnostic` 在下载页和 Drive 页顶部显示版本/构建，授权错误也附带该标识。Drive 页菜单“应用与授权诊断”会读取**实际安装包**的 SHA-1，显示 Google Play 服务版本/可用性及本次授权返回码；点击“复制诊断信息”后可用于核对。它不读取账号、令牌、文件列表或下载链接。

### 不替换原应用，检查手机实际安装包

CI 另提供 `GDirectLite-inspector-RUN-ATTEMPT` 工件，包名为 `dev.gdirect.lite.probe`，应用显示名称为“GDirect 安装包检查”。这是独立、只读的离线检查工具，可与原应用并存；不需要为 `.probe` 注册 OAuth 客户端。

安装并打开检查工具，点击“复制诊断信息”。它从 Android PackageManager 读取当前用户空间中 `dev.gdirect.lite` 的实际版本、公开证书 SHA-1，并查询其公开资源中是否存在旧取消提示/新回调提示标记。它不替换原 APK，不访问其私有设置、账号或令牌，不进行授权。独立 `inspector` 模块未申请 INTERNET 权限，也不包含主应用的登录/下载代码；CI 会对最终 APK 验证这些边界。

因此，检查工具自身的新证书不会影响原应用已登记的 OAuth 签名，也无需为了采集诊断而重配 Cloud。若应用位于工作资料或分身空间，检查工具需运行在同一用户空间；找不到目标包时会明确显示，不能据此断言手机没有安装它。

本次反馈中，维护者报告已为构建 `40187918710` 配置 API、只读权限、测试用户和 Android 客户端，但手机仍显示旧版文案。不能仅凭控制台完成记录确定手机安装包的签名。先核对手机的版本/构建/实际 SHA-1，再决定下一步；不要重复要求已经完成的 Cloud 初始化。

拿到授权后仍需确保 **同一项目** 的 Google Drive API 已启用。文件列表请求被拒绝时也可能涉及权限或配额；没有 Google Cloud 控制台信息或具体服务错误证据，不能把所有 403 都归结为未启用 API。

## 验证清单

- 首次授权成功、取消授权、未授予所需范围；没有密码输入框或自制网页登录页。
- 测试账号能浏览 My Drive 根目录、进入文件夹、返回上级、在当前文件夹内搜索、加载下一页。
- 用一个内容已知的普通私人文件验证下载、取消、大小及 Drive MD5 校验、MediaStore 保存和系统打开。
- 断开本机后，文件列表清空，旧会话不能继续私人传输；已保存到系统下载目录的文件保留。
- 令牌失效（401）后旧会话失效并清除 SDK 缓存令牌；点击连接重新授权。不把令牌当作错误文字显示。
- 通过菜单“撤销 Google 授权”验证远端撤销；网络失败时按提示在 Google 账号第三方访问设置中移除应用。
- 登录后杀死应用进程并重新打开：本应用不从磁盘恢复令牌，需再次点击连接，由 Google SDK 获取授权令牌。

当前自动测试验证了协议、会话与数据模型边界；模拟器未登录界面检查不能替代上述真实账号验证。未完成清单前，不得把 Google 登录、私人文件下载或所有设备兼容性宣称为已经端到端验证。

## 安全边界和当前限制

- Token 只存在于当前应用进程内存，不写入 SharedPreferences / 数据库 / Bundle / Intent / URL / 日志。Google Play 服务自身的账户及授权缓存由系统组件管理。
- 带 Authorization 的请求仅发往 `https://www.googleapis.com/drive/v3/files` 及受控文件子路径；拒绝所有重定向，使用系统 HTTPS 证书和主机名校验。私人 API 和下载不接收手工 IP 参数。
- 断开或切换会话会使旧列表请求及下载会话失效。启动私人下载的 Intent 仅含文件 ID 和非敏感会话代数。
- 私人下载暂为单流；不会持久化跨会话续传信息。临时内容在应用缓存目录，完成、失败或取消后清理，进程异常退出的残留在下一次启动下载服务时清理。
- 下载在核对元数据大小及可用的 MD5 后才发布到系统下载目录；MD5 用于检测传输错误或版本变化，不是安全签名。
- 暂不支持共享云端硬盘、快捷方式解析、Google Docs/Sheets/Slides 导出、上传、挂载或文件队列。用户选择的普通文件必须允许下载。
- 面向公开用户发布前，应按 Google 当前政策评估 restricted-scope 验证、隐私政策及是否需要安全评估；本应用的 Android OAuth 客户端方案不同于桌面 CLI 的凭据方案。

## 官方参考

- [Android 授权与客户端配置](https://developers.google.com/identity/authorization/android)
- [Drive API 权限范围](https://developers.google.com/drive/api/guides/api-specific-auth)
- [文件列表](https://developers.google.com/drive/api/reference/rest/v3/files/list)
- [下载与导出](https://developers.google.com/drive/api/guides/manage-downloads)
