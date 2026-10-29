# 应用图标

![图标设计预览](icon-preview.png)

概念为蓝色文件夹与白色转向传输箭头，和应用的蓝色 Material 主题保持一致。此项目委托生成的独立图形，没有使用 Google Drive 三角商标或参考项目资产。

- `icon-preview.png`：完整配色预览，由图像生成工具创建。
- `app/src/main/res/drawable-nodpi/ic_launcher_art.png`：经同一工具处理的透明前景，符号约占画布中央 54% 宽度，为不同启动器遮罩留出边距；打包时不改变原图。
- `drawable/ic_launcher_foreground.xml`：Android 位图前景；背景颜色为 #EAF2FF。
- `drawable/ic_launcher_monochrome.xml`：独立绘制的简化文件夹/箭头挖空矢量，用于 Android 13+ 主题图标；v33 自适应资源引用。

Android 10–12 使用普通自适应图标；Android 13+ 可由支持的启动器选择主题图标。尚未在实际启动器截图验收，不声称所有遮罩和 OEM 均已通过。源图作为项目自有资产随项目许可证分发；GTS 根证书和其他依赖的独立许可不变。
