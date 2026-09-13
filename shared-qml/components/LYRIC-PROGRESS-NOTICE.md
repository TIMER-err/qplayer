`LyricProgress.qml` 保留自原 `md3/Core/LinearProgress.qml`，迁移时仅接入 Miuix 主题。以下为该实现随原组件目录保留的来源声明。

# Material Components QML 版权与来源声明

本目录最初基于 sudoevolve/material-components-qml 的基础 Material Design 3
QML 组件：

- 上游仓库：https://github.com/sudoevolve/material-components-qml
- 项目官网：https://meow2030.github.io/Qtcraft/

上游组件的原始版权归 material-components-qml 的作者及贡献者所有。

## QPlayer 修改版

本目录不是上游仓库的原样副本，而是 QPlayer Contributors 持续维护的修改版。
为适配 qml4j 引擎和 QPlayer，目录内文件已在组件行为、布局、输入处理、动画、
主题和应用集成等方面进行修改，并包含 QPlayer 新增的组件。各项修改及其作者
以本仓库 Git 历史为准；QPlayer Contributors 仅对各自完成的修改部分保留版权。

上述修改不取消、替代或重新许可上游作者对原始实现享有的版权。

## 上游公开使用说明

上游仓库目前没有提供 LICENSE 或 LICENSE.md 文件，也没有声明 MIT、Apache、
GPL 等标准软件许可证。上游官网对 Community 版本公开说明如下：

> Community — Free
>
> 开源免费，适合个人学习与非商业项目。
>
> - GitHub 开源访问
> - 基础 MD3 组件
> - 社区支持

官网同时说明 Community 版遵循开源协议，但未给出具体协议名称或完整协议文本。
因此，本声明仅如实记录上游公开信息，不将该组件推定或重新标注为任何标准开源
许可证，也不授予上游公开说明之外的额外权利。QPlayer 根目录的 Apache-2.0
许可证不应被理解为对本目录上游原始代码的重新许可。
