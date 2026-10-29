# 交付门槛

## 固定身份

当前预览包为 dev.gdirect.lite.preview，0.7.2/versionCode 11；证书只以 config/preview-signing.json 为准。沿用原私钥，缺失或不匹配时停止打包，不能创建替代品。维护者已报告完成客户端配置，普通更新不要求再登记 OAuth。旧主包的不同签名不能覆盖安装。

## 提交与检查

1. 所有修改在开发分支，通过 PR 审阅；不强推或自行合并 main。
2. `bash tools/check.sh` 通过独立检查、两变体 JVM 测试、编译和 Lint。网络变更须有实际 TLS/协议边界证据，不只测试成功路径。
3. 干净且已提交的源码由 `tools/build-preview-apk.sh` 用保留私钥打包；核对 APK 的提交、版本、包名、证书、诊断资源和 Google 根包。仅交付 verified-preview/ 中结果，不交付随机主 debug 包。
4. GitHub Actions 对最终提交成功；PR 描述说明行为、验证和未验证部分。APK/ZIP 不包含私钥或账号凭据。

## 手机验收

按 [ACCEPTANCE.md](ACCEPTANCE.md) 检查安装覆盖、初次授权、只读/管理、文件功能、错误恢复、UI/启动器、代理关闭后的实际网络和内容校验。当前没有 0.7.2 设备截图、真实账号和中国网络结果，不以自动测试代替这些项目。

一个完整阶段后统一交付，不反复给中间包或要求 Cloud 配置。签名在当前云环境确认保留，但外部备份和 CI Secret 未落实，不能承诺永久备份。旧阶段证据保存在 [历史记录](history/RELEASE_GATE_THROUGH_0_5.md)，不代表当前验收状态。
