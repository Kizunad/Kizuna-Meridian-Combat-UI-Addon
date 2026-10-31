# 战斗 UI v1.1

已实现战斗架势、客户端十槽分档、`/meridian combat slot` 槽位指令（服务端节点下发、客户端本地保存）、施放请求和已学列表同步。
v1.1 增加第一人称/越肩两模式、镜头独立绕看、镜头相对移动、人物限速转身和方块遮挡拉近。
槽位是输入偏好；身体、知识、费用、冷却、绑定与效果由功法服务和实体宿主裁定。

- [使用方法](Usage.md)：安装、命令、按键、本地文件。
- [构建与验证](Verification.md)：隔离依赖、构建命令、测试边界。
- [网络与配置契约](Contracts.md)：消息、线程、限流、文件保护。
- [整合接口与方案问题](Integration.md)：功法库、Human、PA 和 CombatJourney。
- [v1.1 本地交付记录](Delivery-v1.1.md)：本次文件变更、命令结果、代码形状自查。
- [Windows 试玩交付](Delivery-Windows-Trial.md)：无槽架势入口、最新测试与候选 JAR 切换。
- [Windows 试玩指南](Windows-Combat.md)：当前整套候选切换、卷轴、命令、按键与验证边界。
- [图标栏交付](Delivery-HUD.md)：十槽图标、冷却与不可用投影、当前依赖及验证边界。
- [当前工作区整合候选](Integration-Candidate.md)：完整玩家路径测试与 Windows 候选打包入口。
- [首版本地交付记录](Delivery.md)：首版文件清单与当时验证结果。

源码分成 `shared/` Protobuf、`core/` Java 17 逻辑和 `fabric/` Minecraft 适配。
当前版本增加架势内十槽图标 HUD，保留动作栏提示；没有接入动画、连招或预输入。
模组命名空间为 `meridian_combat`。
