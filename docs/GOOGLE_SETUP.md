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

CI 的 `GDirectLite-debug-APK` 工件同时包含 `signing-certificate.txt` 和 `SHA256SUMS`，可查看该工件中 APK 的实际 SHA-1 及 SHA-256，不包含签名私钥。

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
