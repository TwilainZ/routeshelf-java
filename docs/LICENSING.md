# 许可证与归属

## 当前授权

本仓库当前版本的项目自有源码、文档和图标采用 [GNU General Public License v3.0 only](../LICENSE)（SPDX：`GPL-3.0-only`）。GPL 允许个人或组织使用、研究和私下修改。将原版或修改版交给他人时，应遵守 GPL，包括保留许可与版权声明，并在分发二进制时按 GPL 第 6 条提供对应源码。修改版中受 GPL 约束的整体须按同一许可证分发。接收者可以继续分享源码，分发者无需向原仓库提交改动。

Android 使用 Google Play 服务的客户端库。项目在 GPL 第 7 条下授予 [有限链接例外](../LICENSES/Google-Play-Services-exception.txt)，只覆盖与 Google Maven 发布的未修改客户端库组合的情况。分发者仍须按 GPL 提供本项目及其 GPL 修改的对应源码，并遵守 Google 库自己的条款。

## 分发安装包

- 保留包内的 GPL 全文、版权声明、适用的链接例外和第三方声明。将修改及其日期记录在源码中。
- 公开提供 APK、ZIP 或 DMG 下载时，在相同下载位置清楚标出取得对应源码的方法，并以 GPL 第 6 条要求的方式提供源码。使用包的构建清单中记录的提交，或同时提供包含实际修改、构建脚本和必要资源的源码归档。
- 核对适用于被分发组件的源码和许可证要求。应用签名证书、包名和 Google 授权配置有独立的用途。构建源码应能使用分发者自己的开发签名；项目的预览私钥和 OAuth 凭据由各自所有者保管。

正式条款见 [GPL-3.0 全文](../LICENSE)。上述说明用于帮助使用仓库和交付件。

## 历史授权

2026-10-25，维护者基于当时的独立实现和归属审查，选择 Apache-2.0。当前 GPL 授权从本次换证提交生效。已按 Apache-2.0 获得的旧版本保留原许可权利；可在 [换证前的提交](https://github.com/qingzhuernest/routeshelf-java/tree/4b99941b187ea8ae0b0ad04eeff1063e01be2de4) 查看当时的许可证和源码。仍需使用的 Apache-2.0 原文放在 [LICENSES](../LICENSES/Apache-2.0.txt)。

[最初的 Java 原型 ZIP](history/prototype/) 保留原始字节及其中的 GPL-2.0-or-later 条款。原型用于查阅历史，构建使用当前 Git 源码。

## 来源与第三方条款

- Android 版本从本仓库的独立 Java 原型演进。
- 项目参考了 `mizorewww/gdirect` 的公开功能、文档与协议行为。源码、补丁、种子、测试与界面资产由各自项目维护。本仓库中的前端引导候选在公开报告所述范围内独立选择，出处见 [数据连接设计](DIRECT_NETWORK.md)。
- Google Play 服务组件采用各自条款。Apache-2.0 依赖、Gradle Wrapper 与其他组件的声明列在 [第三方声明](../THIRD_PARTY_NOTICES.md)。
- Google GTS 根证书子集来自 Mozilla NSS，保留 MPL-2.0 及来源声明。参见 [证书来源](TRUST_ROOTS.md)。应用图标的源文件和归属见 [图标记录](design/README.md)。

引入外部源码、二进制、资源或依赖时，先审查其来源、许可和分发条件，再更新项目声明。
