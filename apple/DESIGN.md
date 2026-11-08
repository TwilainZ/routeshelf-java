---
name: GDirect Lite macOS
description: 以可复用 Swift 核心为主、使用原生 macOS 控件的只读文件客户端。
spacing:
  standard: "16pt"
  transfer: "18pt"
  sheet: "24pt"
  detail: "28pt"
---

# Design System: GDirect Lite macOS

## Overview

**Creative North Star: "熟悉的原生文件工具"**

模式 Operate。延续 GDirect Lite 自有身份，以 SwiftUI/AppKit 的系统导航、表格、表单和文件选择器表达文件访问；界面保持薄，产品能力由可复用的 GDirectCore 与平台适配 GDirectApple 提供。这里记录 `Sources/GDirectMac/MacApp.swift` 已实现的 Apple 界面，不修改根目录 Android 设计系统。

用户偏好熟悉的官方应用操作习惯；本平台以原生 macOS 控件落实该方向。保留自己的名称、图标和 SF Symbols，不移植 Android Material 的底部导航、按钮形状或资产。当前是只读开发预览，不承诺上传、修改、队列、离线同步或原生云端预览。

**Key Characteristics:**

- 原生侧栏、文件表格、工具栏与独立 Settings 场景。
- 系统语义颜色、字体、选择、焦点和禁用状态。
- 账户授权、线路验证与下载结果分别表达。
- 当前/最近一次进程内传输，只展示真实进度与可用动作。

本记录基于源码提取。Linux 的 31 项共享核心测试通过不代表 Apple 框架编译、原生渲染或视觉验收；缺少真实 Mac 截图时仍需从运行中的 App 补采。`--ui-smoke` 示例必须明显标注，不能作为真实账户证据。

## Colors

颜色由 macOS 语义样式与控件外观解析，随系统日夜、强调色及辅助功能设置变化；源码没有自定义十六进制调色板，因此 frontmatter 不固化颜色值。

### Primary

- **系统强调色**：原生选中状态与 `.borderedProminent` 的连接、下载和打开文件动作继承系统外观，未在应用中固定为蓝色。
- **系统错误红**：公开链接 sheet 中的原位错误使用 `.red`，同时保留可读错误文字。

### Neutral

- **主要文字**：文件名、状态和表单主体继承默认前景样式。
- **次要文字**：大小、帮助、线路说明和传输字节量使用 `.secondary`。
- **轻提示底色**：顶部消息区使用 `.quaternary`；窗口、Table、Form 和 sheet 的其余背景由系统提供。

**The Evidence Rule.** 账户已连接、Google 服务响应和文件下载完成是三种不同证据，任何颜色或文案都不得将其合并为一个成功状态。

## Typography

使用 SwiftUI 系统字体与语义角色，不绑定自定义字体、字号或固定行高。

- **Title**：`.title2` 用于首次连接、公开链接 sheet 及传输详情标题。
- **Body**：默认系统文字用于文件表格、状态、表单标签和操作。
- **Callout**：`.callout` 用于顶部消息、连接线路与部分帮助说明。
- **Caption**：`.caption` 用于链接下载限制、候选地址和钥匙串说明。

文件名称列目前限制单行；大小使用 `ByteCountFormatter` 的 `.file` 样式，未知值显示破折号。一般消息、线路说明和传输状态可选择复制。尚未完成 VoiceOver、长文件名可发现性或超大字体的原生验收，不能把单行截断视为已证明的可访问方案。

## Layout

主窗口为 `NavigationSplitView`：左侧 List 固定提供我的云盘、最近修改、传输、连接；右侧按选择呈现内容。文件页标题显示当前文件夹，其他页使用目的地名称。窗口最小尺寸为 780 × 520 个 SwiftUI 点；侧栏宽度最小 160、理想 180、最大 230 点。正文随窗口扩展，没有实现额外的手机布局或自定义断点。

文件正文以零间距垂直排列搜索行、原生 Table、空状态与分页。搜索行及顶部消息使用系统默认 `.padding()`，不把该默认值误记为应用自定义间距。大小列宽最小 80、理想 100、最大 140 点，文件名称列占用剩余空间。分页按钮使用 8 点外边距。首次连接和传输内容使用 frontmatter 的 detail 内边距并向左上对齐；连接与 Settings 使用 grouped Form，Settings 场景宽 560 点。

公开链接 sheet 使用 sheet 内边距、standard 纵向间距，输入框最小宽度 430 点；底部取消在左、默认下载动作在右。首次连接内容使用 standard 间距，传输内容使用 transfer 间距。frontmatter 的 `pt` 在本文件中指 SwiftUI 逻辑点，不是浏览器 CSS 像素，也不代表控件内部尺寸。

## Elevation & Depth

采用 macOS 窗口、原生 sheet、工具栏、选中态及 grouped Form 的系统层级。应用没有自定义阴影、玻璃材质、背景纹理或卡片阴影令牌。系统外观负责视图层次与交互反馈，不另画假窗口栏。

## Shapes

按钮、Table、分组表单、sheet 和选择器保留系统形状。公开链接输入框显式使用 `.roundedBorder`，主操作使用 `.borderedProminent`；源码没有声明数值圆角，不把系统实现细节冻结成应用 token。SF Symbols 用于侧栏、工具栏和文件类型图标；图标旁保留文字标签或系统可访问名称。

## Components

### Navigation and toolbar

侧栏使用原生 List selection，文件 Table 使用单选及系统右键菜单。文件/最近修改页面的工具栏提供上一级、刷新和下载：上一级依赖文件夹历史，刷新在未连接或加载中禁用，下载依赖选择的文件允许下载且当前无传输。公开链接入口跨页面可用，在传输中禁用。保留 Command-R 刷新、Command-Shift-L 公开链接和标准窗口菜单。

搜索在 Return 提交后请求，不随每次输入刷新。双击文件夹进入目录；双击普通文件先选择保存目录再下载，不能称为云端预览。右键菜单仅给当前对象可用的打开或下载。空列表在加载结束后给出无文件或刷新重试文字，加载时搜索行显示小型系统进度。列表请求由模型持有，过期结果不得覆盖新目的地。

### Account and Settings

未连接时以配置导入、连接 Google 和帮助文字取代表格；连接主操作使用 prominent 按钮，未配置或正在授权时禁用。授权进行中提供取消连接。Settings 为原生 Settings 场景和 grouped Form，保留配置导入、连接/断开及关于信息；导入配置在传输或授权中禁用。

配置经 `NSOpenPanel` 导入；授权在系统浏览器完成，客户端配置和刷新凭据仅存本机 Keychain。界面说明断开只清除本机连接，不能代替 Google 账号撤销。首版只读，不展示不可用的写入控件。

### Public-link sheet

公开链接是自包含 sheet：标题、原生圆角输入框、原位错误、限制说明和取消/下载。取消绑定系统 cancel action，下载绑定 default action；空输入或传输中禁用下载。解析失败保留输入并展示错误，不把错误仅表达为颜色。下载目标通过系统目录选择器选择，默认建议 Downloads。

### Transfer detail

独立侧栏目的地观察当前/最近一次进程内任务，不构造持久历史或队列。准备阶段及未知总量使用不定进度，已知总量使用真实字节进度；进行中提供停止。完成后提供 prominent 打开文件和在 Finder 中显示；无完成文件且无进行中任务时给公开链接下载入口。终态不得被排队的进度回调覆盖。

### Connection form

原生 grouped Form 用 Section、LabeledContent、Picker 和文字分别呈现账户、连接结果、线路与最近验证。进入页面不自动检测；检测按钮在检测或传输中禁用。模式 Picker 同样在检测或传输中禁用；选择已知第三方中转先弹原生确认，明确其第三方身份及可观察的连接信息。

保留 Google 前端、允许已知第三方中转、系统网络三种模式。线路说明不能暗示绕过 VPN，也不能将候选地址或匿名服务验证称为真实文件下载成功。检测完成后展示验证时间；首版没有实现 Android 的五分钟 UI 老化规则，不将该规则移植为已实现行为。

## Do's and Don'ts

### Do:

- **Do** 使用 SwiftUI/AppKit 系统控件、系统字体、语义样式和 SF Symbols，保留标准键盘、焦点与选择机制。
- **Do** 将账户、连接和传输状态分开，并以文字说明空、加载、失败、取消和完成。
- **Do** 保持 GDirectCore 可复用、GDirectApple 负责平台适配、GDirectMac 为薄前端；Android 保持独立的设计与组件。
- **Do** 从真实运行的 Mac App 补采首次连接、浏览/选择、公开链接错误、传输和连接确认状态，并覆盖日夜及窗口最小尺寸；示例数据明确标注。

### Don't:

- **Don't** 复制 Google 或参考项目的界面资产，或以自有名称暗示官方认可。
- **Don't** 用定制卡片、假系统栏或 Android 导航替代当前原生窗口结构。
- **Don't** 伪造进度、队列、历史、预览能力或网络可达性，也不让用户把令牌和 OAuth JSON 发给开发者。
- **Don't** 把 Linux 核心通过、源码复审或浏览器示意图当作 macOS 编译、原生截图或无障碍验收。
