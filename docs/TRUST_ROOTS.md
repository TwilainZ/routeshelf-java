# Google 数据通道的根证书

0.6.0 使用独立的 KeyStore 和 TrustManager，仅接受 GTS Root R1、R2、R3、R4。不会叠加设备、用户安装或代理的 CA，也不会在根证书缺失、过期或指纹不匹配时退回系统信任。TLS 名称验证继续启用：普通/无 SNI 模式验证实际服务名，Google 前端模式验证 g.cn，HTTP Host 保持实际服务名。APK 签名和此信任库是两套独立机制；本次没有更改应用签名。

公开证书取自本环境系统 Mozilla 根证书集合中的 GTS_Root_R1–R4，并校验自签名、有效期、CA 属性和下列 DER SHA-256；没有从参考项目获取证书包。证书集合来源为 Mozilla NSS certdata.txt，保留 MPL-2.0 来源及完整许可：`app/src/main/resources/gts-roots.LICENSE`。公开 PEM 本身保存在同一目录，便于检查和替换。未导入 Debian 的 GPL 转换脚本。

| 根 | DER SHA-256 |
| --- | --- |
| GTS Root R1 | d947432abde7b7fa90fc2e6b59101b1280e0e1c7e4e40fa3c6887fff57a7f4cf |
| GTS Root R2 | 8d25cd97229dbf70356bda4eb3cc734031e24cf00fafcfd32dc76eb5841c7ea8 |
| GTS Root R3 | 34d8a73ee208d9bcdb0d956520934b4e40e69482596e8b6f73c8426b010a6f48 |
| GTS Root R4 | 349dfa4058c5e263123b398ae795573c4e1313c83fe68f93556cd5e8031b3c7d |

这四张根的到期时间为 2036-06-22。应持续跟踪 Google 根轮换与吊销政策，不能等到到期才维护。参考 Google 发行者仓库 https://pki.goog/repository/ 和 Mozilla 集合 https://hg.mozilla.org/projects/nss/file/tip/lib/ckfw/builtins/certdata.txt 。本次从系统集合读取，未声称实时下载发行者网页。`config/google-trust-roots.json` 记录 PEM 包摘要，APK 验证器会核对实际打包内容。

此策略覆盖本应用的 Google 数据、公开 DNS 和范围列表请求，不改变 Google Play 服务内部的授权网络。Google 专用根是信任范围约束，不是物理路由或服务可达性的证明。允许第三方 TCP 中转时仍需验证 Google 链和 TLS 身份；中转的运营方式或可用性变化可导致验证失败，应停止而非放宽校验。

测试覆盖实际 TLS 握手中使用 g.cn、保留真实 Host、错误主机名/非受信链拒绝，以及仅伪造 Google 名称的自建 CA 在发送授权前被拒绝。公网中转和中国运营商线路仍需真机验收。
