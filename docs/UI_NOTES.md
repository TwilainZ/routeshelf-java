# UI 实现与检查

主界面已按维护者指定改为接近 Google Drive 的 Material 文件浏览：默认进入云盘；顶部工具栏与固定搜索；底部首页（最近修改）和文件；右下角新建/上传。文件范围标题的下拉入口提供其他范围、回收站和共享云盘。文件行增加独立类型图标、大小和修改时间，保留两行文件名与更多菜单。菜单保留刷新、排序、公开链接下载、传输和设置。独立设置页统一账户权限、连接、下载偏好和关于；文件列表不重复显示长期权限和诊断文字。

颜色调整为中性日夜表面和蓝色操作，系统无衬线字体。文件列表为浮动按钮保留滚动空间；控件至少 48dp、内容可增长；处理实际系统栏与键盘 insets。管理权限仍明确申请；下载不因单击不支持预览的文件而意外开始；危险操作继续确认。

本轮只有源码审查、真实编译、协议测试和 Lint，没有当前设备截图或 TalkBack 实测，不能说视觉已验收。此前无 KVM 模拟器启动失败，本次未重复。手机验收需覆盖 360dp/横屏、日夜主题、1.3/2 倍字体、键盘搜索与清除、文件菜单、返回/旋转、传输取消、网络恢复和只读管理授权。详见 DESIGN.md。

早期静态审查保存在 [0.4.0 历史记录](history/UI_0_4.md)。

0.7.0 的设置页使用四组 Material 文字列表行；断开状态只显示连接入口，不堆积不可用的账户按钮。模式选择与下载并行数使用单选对话框，检测状态就地显示，退出/旋转后通过应用持有的同一任务恢复观察。隐私和许可在设置内查看，授权诊断保留原浏览页上下文。仍需设备视觉验收。


0.7.3 将设置页的手写文字按钮替换为 AndroidX PreferenceFragmentCompat、PreferenceCategory、Preference 和 ListPreference，模式/并行数使用 MaterialAlertDialogBuilder 单选框。连接、账户、下载、关于四组有标题和独立摘要；模式状态明确显示，检测中切换先取消后应用，传输期间说明限制。返回云盘后按新模式刷新目录。移除旧 settingsRowStyle；日夜模式继续使用 Material 3 语义颜色。

参考 Google 官方 Material Android 文档 [Dialogs](https://github.com/material-components/material-components-android/blob/master/docs/components/Dialog.md)、[Radio buttons](https://github.com/material-components/material-components-android/blob/master/docs/components/RadioButton.md)，以及 [AndroidX PreferenceFragmentCompat](https://github.com/androidx/androidx/blob/androidx-main/preference/preference/src/main/java/androidx/preference/PreferenceFragmentCompat.java)。组件编译与 Lint 已验证；本环境未执行手机视觉验收。


0.7.5 增加 Material 连接状态页和云盘顶部实时文字入口，使用官方工具栏、不定进度、按钮、分隔线及语义文字样式。账户、API 和下载独立成行，错误同时用文字表达；检查时间、VPN 和模式就地显示，底部提供日志。状态页可滚动、大屏限制内容宽度；所有操作至少 48dp。运行状态与文案有 JVM/真实回环 TLS 测试，未将静态布局或编译当作手机视觉验收。


0.8.0 完成浏览/登录/传输的结构重排，以及公开下载页的 XML Material 表单化；新布局为 activity_transfers.xml、上级图标按钮为 view_folder_up.xml。状态与操作仍复用现有会话和传输控制器。连接技术详情默认折叠，手动 IP 展开状态恢复。已有真实编译/Lint 和各变体协议测试；未执行本版手机截图/触控验收，需覆盖键盘、大字体、登录/恢复/失败、长文件名、分页与传输回看。

0.8.2 根据维护者手机截图修正入口：浏览页“查看传输”、菜单与通知直接打开独立 TransferActivity，不再进入公开链接表单；该页没有输入框和自动键盘。当前/最近一次任务优先展示状态，下载完成提供打开文件，进行中提供停止和真实进度，失败提供日志；空任务不显示虚构进度。表单只负责链接输入，验证后开始下载并进入详情；默认不自动聚焦输入。星标/共享底部目的地已移除；次级文件范围和 API 能力保留。新页面尚无设备截图，本地 ADB 未发现设备，未重复启动已失败的软件模拟器。
