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
  reserved 4; // 原 cooldown_remaining_millis，已停用
  int64 cooldown_total_millis = 5;
  string unavailable_reason = 6;
  string description = 7;
  string volume_id = 8;
  string volume_name = 9;
  string book_name = 10;
  int64 cooldown_end_tick = 11;
  BlockKind block = 13; // 现在放不出的原因：BLOCK_NONE、BLOCK_BODY（身体绑不上）、BLOCK_ITEM（手里的东西不对）、BLOCK_QI（真元不够）、BLOCK_EXERTION（筋肉脏腑没力气）；BLOCK_BUSY（正在放别的式）服务端不发，客户端本地标
}
```

JOIN 后排队发送完整快照；客户端晚注册接收通道时再排队补发；`KNOWN_CHANGED` 在 tick 结束合并为完整快照。
查询从公开宿主的一次 `Inspection.body` 读取 `TechniqueState`，过滤目录已移除的精确引用，
每个 ID 选择仍在目录中的最高已学版本。名称、简介与图标分别来自该精确版本的
`presentation.display_name`、`description` 与 `icon_id`，不再将简介当作名称。
卷与书名称来自精确引用所属的目录元数据；未分组时三个卷册字段为空。
字段 1–6 编号保持不变，旧包省略追加字段时，窗口显示无简介、无卷分组的空态。
冷却发结束时刻：`cooldown_end_tick` 是冷却结束时的世界总 tick（与客户端 `level.getGameTime()` 同一时基，由身体上的剩余毫秒向上取整换算），
0 表示不在冷却；`cooldown_total_millis` 是功法声明的冷却，只用来画占比。客户端按自己的世界 tick 算剩余，
身体时间和世界同步走时结束 tick 不变，冷却期间不重发列表；字段 4（发包时刻的剩余毫秒）停用并保留编号。
每 5 tick 检查一次在线玩家：宿主答得出轻量读数（`TechniqueHostDirectory.bodyStamp`）且身体版本、
身体时钟相对世界 tick 的偏移、主手和功法目录都没变时不重算；答不出时照旧取一次身体重算冷却与 `TechniqueBinder` 绑定状态。
相同快照不重复发包。
相对手臂绑定使用同一次 `Inspection.heldItems` 中的主手解剖侧，分别检查主手与副手；
任一手位的身体绑定成功就不显示身体不可用。绑得上以后用功法库的只读预检：先 `TechniqueAvailability.holdable`
问手里的兵器、弹药、药瓶对不对（不对写 `BLOCK_ITEM`、「手里的东西不对」），再 `affordable / workable`
问真元付不付得起、筋肉脏腑做不做得了，两只手有一只行就算行；不行时 `block` 写 `BLOCK_QI` 或 `BLOCK_EXERTION`，
`unavailable_reason` 写「真元不济」「气力不继」。真元和力气恢复不改身体版本：上一份投影里有这两类时，读数没变也每 5 tick 重算，直到没有。
换了手里的东西也不改身体版本：两只手各拿着什么的散列算进门控读数，变了就重算。
承接、着地仍由真实施放入口校验；「正在运功」不走这份投影，客户端照功法库同步来的施放状态副本（`meridian_technique:cast_status`）在本地标。

按键不再直接调 `TechniqueService.cast`，改走功法库的输入缓冲 `TechniqueInputBuffer.press`：按早了一点（离能出手不到配置的窗口，默认 400 毫秒）
的键由服务端记着、到能出手的那一 tick 放；记下的那一次不发拒绝文字，等到头没放出来由功法库报原因。
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

格式版本为 3，包含 `format_version`、`last_profile`、`profiles`、`view`。
每档包含 ISO-8601 `last_used` 和正好十个字符串 `slots`，空字符串表示空槽。
`view` 跨档案共享，字段为 `mode`、`shoulder_offset`、`height_offset`、`distance`、`turn_degrees_per_tick`、
`lock_current_view`（布尔值，默认 false）、`auto_face_radius`（默认 50）。
`lock_current_view=true` 表示人物真实朝向与鼠标控制的越肩镜头联动，不冻结相机角度。
目标朝向优先于视角联动，视角联动优先于普通移动转身；第一人称、界面及预览不接管人物朝向。
允许值、默认值和编辑方式见 [使用方法](Usage.md)。模式切换立即沿原有文件保存路径持久化。
格式 1/2 可读取，新增字段使用明确默认值，下次正常保存迁移到 3；格式 1 默认第一人称。
格式 2 缺少原有视角字段仍按损坏处理，格式 3 缺少新增字段同样保护原件。
完整形状见 `CombatConfigFile` 的编码器；不写入已学列表、架势、临时镜头角、进入前视角、冷却或身体数据。
文件读取上限 1 MiB，全部解码校验成功后才发布。

正常写入使用同目录临时文件与原子替换；文件系统不支持原子移动时用完整文件替换。
临时文件在成功和失败路径均清理。解析失败、槽长错误、格式版本不支持时保留原文件，并禁止该会话写回。
LRU 以 `last_used` 排序，相同时刻也不淘汰当前档。

## 姿态设置客户端门面

`dev.kizuna.meridian.combat.fabric.client.CombatSettingsAccess` 只能在客户端线程调用。
`snapshot()` 返回不可变 `Snapshot(view, stance, writable)`；`save(CombatStanceSettings)` 只提交锁视角和半径。
先写候选文件再发布参数，失败返回 `Result(FAILED, message)`，保留原内存配置。
`keyMapping(Action)` 返回原版注册映射；`saveKeys(Keys)` 沿原版 options 保存并读回检查目标键位。
两个文件各自提交，UI 须逐项显示结果，不能声称跨文件原子保存。

`previewUnavailableReason()` 返回不可开始的原因；`beginPreview()` 返回 `Preview` 令牌。
令牌的 `step(Axes, nanoTime)`、`reset()`、`draft()`、`clipped()` 供 UI 编辑及展示；
`save()` 成功才结束覆盖，失败保持草稿；`close()` 幂等取消。
失效后 `active()` 为 false，不可通过令牌恢复战斗会话。
UI 在关闭、录入和切屏时调用 `clearInput()` 清理排队按键与跨屏幕长按。

Combat 不依赖 Meridian UI、Inventory UI 或 owo。目标 UUID、实体引用、镜头角度和草稿不持久化，
不新增服务端协议。使用方式见[姿态设置](Stance-Settings.md)。

### 普通槽运功开关

已学投影的 `maintained_breathing` 标识运功式。`active_practice_id` 在起式完成后携带当前维持身份。
普通槽发送独立递增的 `practice_press_id`，起式时 `end_practice_id` 留空；再次点按时带回投影中的身份。
服务端按连接拒绝旧序号，收势时同时核对实体、功法和施放身份。格挡继续使用自己的 `press_id` 通路。

## 施放拒绝的动作栏文字

普通按键失败与预输入到期共用功法库的回执文字。回执含 `translation_key` 时，客户端用当前语言显示对应条目，缺译时显示 `reason`。仅含原因的旧回执直接显示原因。
