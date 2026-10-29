# 仓库协作约定

- 沟通、文档和意图注释使用简体中文；公开标识符使用英文。
- 遵守 `~/.codex/AGENTS.md` 的 Java 代码形状规则，每个方法与构造器写中文意图注释。
- `shared/` 只放 Protobuf；`core/` 是 Java 17 纯 Java；`fabric/` 适配 Minecraft 1.20.1。
- 硬依赖功法框架公开接口，不依赖 Human、Inventory UI 或具体功法内容。
- 槽位只保存在客户端；服务端逐次调用 TechniqueService 校验，不持有槽位。
- 范围为架势、十槽、客户端命令、动作栏和第一人称/越肩两模式，包含十槽图标 HUD；不做动画、连招或预输入。
- 镜头、移动换算与模式会话逻辑放 core；客户端 Mixin 只连接原版输入、实体和渲染。
- 网络线程只接纳限流后的有界字节，服务端线程解码并处理。
- 文档唯一编辑源为 `docs/wiki/`，README 仅作入口。
- 交付执行 `./gradlew test build`、`./gradlew :fabric:runGametest`、文档检查与 `git diff --check`。
- 不修改依赖仓库；提交、推送和远端建仓分别按用户授权执行。
- 原创部分采用 PolyForm Noncommercial 1.0.0，保留第三方许可。
