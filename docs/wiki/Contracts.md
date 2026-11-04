# 网络与配置契约

## 施放通道

`meridian_combat:press` 是 C2S 原始 Protobuf 字节，不加字符串或长度前缀。
消息为 `TechniquePress { string technique_id = 1; float move_x = 2; float move_z = 3; }`。
服务端不接收槽号、身体 ID、版本、费用、效果值或时钟。

接收回调在服务端线程执行，先拒绝空包、超过 1024 字节的包，以及同一玩家滑动一秒窗口第 21 个请求，
再解码并校验完整命名空间 ID、有限浮点，把非零方向归一化，生成施放 UUID。
身体身份来自公开 `TechniqueHostDirectory`；精确版本取目录中仍存在的最高已学版本。
未学时交给服务校验目录最新版本，目录外同样经 `TechniqueService.cast` 返回拒绝。
零向量原样保留。`CastOutcome.reason` 原样通过动作栏回传；非法载荷与限流包直接丢弃。

v1.1 不改变消息或协议版本。越肩下有移动输入时填人物当前 yaw 的水平正前方；
无输入仍发零，宿主沿人物默认方向解释。第一人称保留首版移动方向语义。
镜头角度、模式、进入前视角均不进入协议；原版实体移动包继续发送人物真实 yaw/pitch。
原版近战与功法目标射线沿人物眼位和人物角度，独立相机位置不参与射线计算。

每个玩家独立计数，断线释放窗口，关服清空。服务端不存槽位，每次施放均由权威服务校验。
架势只是客户端输入模式，不构成权限，也没有服务端架势状态。

## 已学列表

`meridian_combat:known` 是 S2C 原始 Protobuf：

```proto
message KnownTechniques { repeated KnownTechnique techniques = 1; }
message KnownTechnique {
  string id = 1;
  string name = 2;
  string icon_id = 3;
  int64 cooldown_remaining_millis = 4;
  int64 cooldown_total_millis = 5;
  string unavailable_reason = 6;
  string description = 7;
  string volume_id = 8;
  string volume_name = 9;
  string book_name = 10;
}
```

JOIN 后排队发送完整快照；客户端晚注册接收通道时再排队补发；`KNOWN_CHANGED` 在 tick 结束合并为完整快照。
查询从公开宿主的一次 `Inspection.body` 读取 `TechniqueState`，过滤目录已移除的精确引用，
每个 ID 选择仍在目录中的最高已学版本。名称、简介与图标分别来自该精确版本的
`presentation.display_name`、`description` 与 `icon_id`，不再将简介当作名称。
卷与书名称来自精确引用所属的目录元数据；未分组时三个卷册字段为空。
字段 1–6 编号保持不变，旧包省略追加字段时，窗口显示无简介、无卷分组的空态。
每 5 tick 刷新在线玩家的冷却与 `TechniqueBinder` 绑定状态，相同快照不重复发包。
服务端仅缓存最后发送的投影用于去重，断线与停服清空；不缓存身体、不接收客户端槽位。
非玩家实体的学会通知不发网络包。

单包仍最多 1 MiB、4096 条，每个 ID 最长 256 字符，名称、卷名、书名及不可用原因最长 1024 字符，
简介最长 8192 字符。卷 ID 非空时按功法 ID 规则校验。ID 不得重复，图标 ID 不得包含空、`.` 或 `..` 路径段。
冷却须满足 `0 <= remaining <= total <= 86400000` 毫秒，客户端只插值显示，不保存或修改权威冷却。
空字节合法，表示空表。超界整份拒绝，不发布部分内容。
收包回调在客户端线程执行，丢弃超长包后解码并完整替换；断线后旧连接的包由原版丢弃，不会覆盖新服务器缓存。
断线、加入新连接均清空已学缓存；损坏列表解码后清空缓存。网络入口超长包直接丢弃。

## 槽位指令

`/meridian combat slot` 挂在 Framework 的 `/meridian` 注册点（`MeridianCommands.register(Branch.COMBAT, "slot", PLAYER, …)`）。
客户端不注册 `meridian` 根：Fabric 客户端分发器解析失败时只忽略「未知命令」与「解析异常」，同名根会让 `/meridian body status`
等服务端子命令在客户端报错而不转发。所以由服务端解析，再经 `meridian_combat:slot` 把操作发回执行者本人。

`meridian_combat:slot` 是 S2C 原始 Protobuf：

```proto
enum SlotOperation {
  SLOT_OPERATION_UNSPECIFIED = 0;
  SLOT_OPERATION_SET = 1;
  SLOT_OPERATION_CLEAR = 2;
  SLOT_OPERATION_LIST = 3;
}
message SlotCommand { SlotOperation operation = 1; int32 slot = 2; string technique_id = 3; }
```

服务端只接受玩家执行；客户端没有声明该通道时指令失败并提示，不静默丢弃。功法补全取最近一次推送给该玩家的已学列表。
客户端拒绝空包与超过 1024 字节的包，只接受三种操作，设置与清空的槽号须为 1–10；功法格式与已学校验仍由客户端槽位语义层完成，
结果与原客户端命令相同：设置、清空与失败原因走动作栏，列表进聊天区。收包回调在客户端线程执行，旧连接的包在断线后由原版丢弃。

## 本地文件

客户端 `CombatSlotAccess` 是窗口接入的唯一门面，所有调用与订阅回调都在客户端线程：

- `snapshot()` 返回不可变连接状态、`KnownTechniques` 与十槽 ID 列表。
- `set(number, techniqueId)`、`clear(number)` 与槽位命令共用已学校验、当前世界配置和保存入口。
- 返回 `Result` 的状态为 `SAVED`、`SESSION_ONLY` 或 `REJECTED`，窗口须显示保存失败消息。
- `subscribe(Runnable)` 仅在快照变化时通知，返回的 `AutoCloseable` 必须随窗口释放。
- UI 消费主键盘数字后调用 `consumeConfigurationKey(key)`；持续按住关屏时，直到物理释放前丢弃同键排队点击。
- 断线发布空已学和空展示槽位，但保留文件中的各世界分档；断线写入会被拒绝。

门面不暴露可变集合，也不提供施放能力。Combat 不依赖 Inventory UI，窗口在 Meridian UI 客户端实现。
游戏屏幕打开期间清理槽位键的排队次数，配置数字不能留到关闭后的游戏帧施放。

格式版本为 2，包含 `format_version`、`last_profile`、`profiles`、`view`。
每档包含 ISO-8601 `last_used` 和正好十个字符串 `slots`，空字符串表示空槽。
`view` 跨档案共享，字段为 `mode`、`shoulder_offset`、`height_offset`、`distance`、`turn_degrees_per_tick`。
允许值、默认值和编辑方式见 [使用方法](Usage.md)。模式切换立即沿原有文件保存路径持久化。
格式 1 可读取，默认补第一人称配置，下次正常保存迁移到 2；格式 2 缺少视角字段按损坏处理。
完整形状见 `CombatConfigFile` 的编码器；不写入已学列表、架势、临时镜头角、进入前视角、冷却或身体数据。
文件读取上限 1 MiB，全部解码校验成功后才发布。

正常写入使用同目录临时文件与原子替换；文件系统不支持原子移动时用完整文件替换。
临时文件在成功和失败路径均清理。解析失败、槽长错误、格式版本不支持时保留原文件，并禁止该会话写回。
LRU 以 `last_used` 排序，相同时刻也不淘汰当前档。
