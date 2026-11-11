# GTNH 移植技术设计：以持续同步成本为第一约束

状态：2026-11-11。独立构建、固定图显示诊断、共享几何、现代快照雾色RGB边界、共享外观核心及section解码缓存/掩码核心已实现；真实地图接入仍待完成。可执行命令及已知失败见[构建说明](../gtnh/README.md)。源码证据基线为 `8e8a21b`；始终参照最新规范实现，不建立旧版算法分叉，也不提前适配Vulkan。本文的目录、任务、接口名称除已存在者外均为拟议，不能当作已可运行命令。

## 1. 设计决定

采用 **同仓库、同一份规范源码、独立旧版构建、少量平台实现、受控的小切口解耦**。

- 不复制地图引擎到另一个项目；根 `src/main` 保留上游位置与26.3开发视角。
- 不将GTNH硬塞到当前Stonecutter/ModDevGradle的版本列表。旧版构建隔离，但从同一工作树显式选取共享源码。
- 不先创建通用Minecraft模拟层或全量中立DTO。不在 `net.minecraft.*` 下伪造现代类，让编译器替我们发现真实耦合。
- 允许按目标选择同名平台边界类；不靠源码目录顺序或JAR去重“覆盖”类。
- 含大量算法的混合类优先抽出少量provider，而不是把整文件变成旧版自维护副本。
- 构建生成物可自动生成，源码变换不能成为另一个大型移植框架：默认直接共享；确需Stonecutter时使用官方预处理，不自行正则解释条件语法。

评价顺序：未来需要重新移植的算法量 > 平台接口变动面 > 同步审查成本 > 初次实现速度。多写一个稳定小接缝，比少写几十行而长期复制上千行更划算；反之没有实际消费者的抽象不建立。

## 2. 代码组织与构建边界

拟议布局：

```text
src/main/...                   上游规范源码，位置不搬动
settings.gradle.kts            现有现代版本构建，继续保留
stonecutter.gradle.kts         主视角仍为26.3
gtnh/
  settings.gradle[.kts]         独立GTNH构建根，具体DSL随锁定模板
  build.gradle[.kts] / wrapper  独立锁定GTNHGradle及Gradle版本
  source-policy.json           共享/替换/暂不接入清单与审查基线
  src/main/java/...            旧版入口、平台提供者、GUI薄壳
  src/main/resources/...       旧版元数据、语言/资源适配
  src/test/...                 契约、fixture和构建审计测试
  build/                       生成源码、生成资源及构建产物（忽略）
.local/...                     本机路径、下载、截图、测试运行产物（忽略）
```

构建输入应显式区分：

1. **直接共享**：规范源中的完整文件，允许record/现代Java；避免为Java8手改业务源码。
2. **预处理共享**：含版本条件的文件，由固定版本Stonecutter、固定来源节点生成；只选清单中的输出。初期优先直接共享，不能依赖另一轮构建碰巧留下的缓存。
3. **共享且一次性解耦**：原路径文件中只保留窄接缝及平台委派；改动单独提交，现代行为必须保持。
4. **平台实现**：GTNH自有文件；必要时与上游类具有相同完全限定类名(FQN)，但两个目标不同时编入。
5. **暂不纳入**：现代加载器、兼容模组、GPU/存档扫描/共享等尚未接入的实现；不能用空实现悄悄宣称支持。

独立构建不允许 `**/*.java` 整树导入。为每个最终FQN产生来源清单，必须恰好一个定义。对共享源与替换源分别筛选，避免全局exclude连本地同路径文件一起排除。

共享源若需要物化到build目录，复制完整文件且记录输入hash；不手工维护生成树、不提交生成树。生成manifest必填：当前移植commit及dirty输入摘要、规范源blob/hash、Stonecutter锁版本、源节点/loader/constants、变换顺序和最终FQN来源。记录的上游SHA只是审查基线，不得绕过移植工作树中已新增的共享接缝。预处理失败、输入缺失、重复FQN、依赖泄漏时立即停止，不能“尽量构建”。不以运行时反射大面积桥接编译差异。

## 3. 源码分层：保留已有契约，而非另造数据模型

| 区域 | 策略 | 必须防止的长期负担 |
| --- | --- | --- |
| Projection、MapStyle、TileKey/TileImage、Lighting、Mipmaps、FaceColor、ShapeFaces等 | 原路径共享；按依赖闭包逐项加入 | 为旧Java语法抄第二份算法 |
| ChunkSource、PinnedSource、TurnedSource、TallChunks | 共享现有快照契约；替换快照提供者 | 只见接口没有MC import，就误认为传递依赖已解耦 |
| ChunkSnapshot、BlockLooks、BiomeTints | 旧版实现消费者实际需要的方法/字段；不模拟现代palette/Holder | 为不参与旧版构建的现代调用者补齐整个API |
| Looks、VoxelReader | 一次窄解耦，保留算法/数组/缓存，平台负责规则与section编号 | fork数百行混合算法文件 |
| TileBaker | 保留追踪主体；将下界fog等小段游戏语义委派出去 | 复制整份追踪器，或假装其完全不依赖游戏类型 |
| Facing、MapPick、TileSpots、Overlays | 数学/编码部分共享；坐标包装、方块旋转及游戏规则小接缝 | 为一个BlockPos或随机数入口复制大段数学逻辑 |
| TileStore、TileLabel、SeenBlocks等 | 尽量共享；补齐日志及坐标边界后再纳入 | 改像素格式以绕过平台代码，增加存储兼容债 |
| MapSession / MapLevel / MapViewport | 按方法审计；平台生命周期、GPU/compat调用分离，保留任务/瓦片/LOD主体 | 一次性接入全部现代依赖，或直接重写所有调度算法 |
| MapScreen / MinimapHud / 设置及路点界面 | 旧版GUI薄壳；复用可拆出的行为/数据，不模拟现代GUI框架 | 整份千行屏幕复制后长期同步两遍业务 |
| MapTexture / MapCanvas | 沿用像素上传、quad/裁剪契约；旧版实现，移出MC参数 | 以“立即绘制实现”替换就误认已兼容LWJGL2 |
| GpuMesher / GPU后端 | 后置；抽native buffer生命周期，复用网格/多pass算法 | Java8 Cleaner、LWJGL及GL状态问题混入第一步 |

这些是目标分类，不是“已经能编译”的白名单。每个新增共享文件必须完成依赖闭包检查，包含完全限定类型、静态初始化、嵌套类型和方法体，不能只扫描import。

### 3.1 快照与编号

保留现有“ChunkSource -> ChunkSnapshot -> VoxelReader -> integer look-id”的消费模式，先不把每个voxel改成对象或虚方法调用。

- 采集：客户端线程复制旧section的block/meta、光照和biome；后台只读私有快照。不能持有仍会变化的世界/section引用。
- 编号：会话内建立稳定的Block+metadata到look-id映射；空气与UNKNOWN明确区分。上下文影响外观时扩展采集侧的外观键，不把该问题藏进追踪器。
- 持久化：look-id不是跨会话身份；瓦片存像素/拾取信息。将来保存原始方块数据时必须用可重建注册名+metadata等稳定信息，不能直接落盘运行时编号。
- 保留min/maxY、topBelow、roof、turned、列染色等共享消费者契约；旧版biome是二维，不复刻现代三维palette/fuzz。
- 旋转必须同时覆盖空间坐标与方块外观/形状朝向；不允许仅旋转坐标、楼梯颜色或侧面却保持原方向。

### 3.2 Looks：规则填充与数组消费分离

现有 `kind/tint/emission/top/side/shape/wetShape/shapes` 数组是正确接缝，保留字段布局和按需ensure发布方式。

已实现窄契约：现代 `StateLooks extends Looks`承接状态分类/编号/形状采集；共享 `Looks`保留数组、`ensure` acquire、`publish` release与`internShape`。现代BlockLooks/TableLooks保留原锁和填充流程。旧版提供者尚未实现，届时只读取已准备的外观资料。

- 追踪循环继续直接读数组，不为每像素创建DTO或执行平台类型判断。
- 现代BlockState分类、spawn/fluid/shape查询、颜色及注册表编号移到现代提供者；旧版提供者使用旧游戏数据。
- `ensure`、并发发布、shape去重、纯颜色处理等算法仍共享；避免抽一层接口却把全部Looks逻辑复制出去。
- 先完成条目其余字段，再发布kind；资源重载生成新的外观代次，旧任务结果失效。
- 线程门禁：后台首次ensure/fillLook只能读取已准备的不可变外观资料，不得访问活世界、TileEntity或调用GL。必须主线程执行的颜色/形状/纹理查询在采集或准备阶段完成；缺失时显式pending/retry，或使用有标记的保守结果并触发重烘焙，不得后台同步等待渲染线程。
- Java8 acquire/release转换与真实运行测试是单独门槛，不以“编译成功”替代并发语义验证。

### 3.3 VoxelReader：section入口与读取算法分离

保留job-local缓存、最近section、solid/partial mask及高度等算法。只隔离现代palette/BitStorage读取与编号入口。

拟议section契约提供：单一uniform look-id，或按需解码至现有索引格式的4096整数；索引继续为 `(y << 8) | (z << 4) | x`。现代packed快路径可保留，不强迫所有平台使用同一种内部存储。

- 不要求快照采集时展开所有section、不要求启动时预烘焙所有状态。
- 每个任务固定自己读到的快照；UNKNOWN=-1与AIR编号不混用。
- `solid`多值语义、mask身份哨兵、嵌套类型及包级字段均视为契约，不能只检查public方法签名。
- 抽provider不得在每个光追步产生分配；基准比较其开销后再确定最终边界。

### 3.4 易遗漏的小接缝

- TileBaker的 `fogColor(Holder<Biome>)` 及下界常量选择移到快照/biome提供者，追踪器继续消费RGB值。现代下界行为先做回归，不能为了旧版直接删掉现代分支。
- Facing的数学变换保留，BlockPos包装及BlockState旋转交给平台工具。MapPick/TileSpots同理：保留整数坐标/打包，游戏位置对象在边缘包装。
- Overlays的slime随机入口及刷怪规则按版本实现。现代“blockLight==0”不能自动当成1.7.10规则；恢复该功能时补平台规则及测试，首期明确不启用。
- MapCanvas的工厂、纹理标识和玩家参数仍泄漏MC类型；先把平台对象转换为画布需要的资源/头像输入，而不是实现一个伪GuiGraphics。

## 4. 同名替换的使用上限与漂移保护

优先用于本来就该平台化的实现（快照/颜色/纹理等），不用于掩盖共享算法fork。

每项替换记录：上游路径、旧版实现路径、替换原因、共享调用契约、上次审查的上游blob hash、语义测试以及维护责任。

- 上游对应文件变化，即使Git merge没有冲突，也进入待审查清单。
- 编译发现字段/签名漂移；fixture捕捉UNKNOWN、光照、旋转、发布和坐标等语义漂移。
- 替换实现只能满足被选入旧版构建的调用面；禁止为了通过现代不相关功能编译不断扩充假类型。
- 如果同名替换开始复制大量数组处理、解码缓存或渲染数学，暂停扩大复制，优先提取小接缝。

## 5. 资源与运行数据

- 像素ABGR、绘制颜色ARGB、mipmap尺寸/coverage及瓦片格式保持已有含义，不因旧版方便而更改。
- 共享图标/纹理等资源；现代lang JSON到旧版.lang使用确定性转换，并审查占位符/转义及翻译键，不做人工双份翻译。
- 旧版mcmod.info、Mixin配置/refmap、pack格式单独维护。现代颜色表数据不能改文件名就当成1.7.10数据。
- 开发/发布运行目录与用户实例分离；测试部署只针对明确授权的专用实例。真实游玩环境保持只读。
- 资源重载/退出/维度切换分别管理epoch；后台完成任务携带epoch，在提交像素与写盘前验证归属，防旧世界任务污染新世界。

## 6. Java 与渲染能力：独立维度

- 现代源码不为Java8改写；构建先用锁定的现代JDK，GTNH发布链再降级并接入GTNHLib stub。
- Java25主开发，Java8是兼容验证通道。第一轮就小范围验证record、集合API、VarHandle，避免拖到完整移植后才发现构建方向错误；完整地图验收仍在CPU闭环之后。
- 单一降级包与multi-release jar先做小实验；校验旧Forge扫描器和Mixin处理类的方式，不能仅依据JVM支持multi-release就认定可用。
- Java版本、LWJGL版本、渲染器和GL能力分别记录；Java25不等于LWJGL3，Angelica也不等于必然提供所有GPU烘焙扩展。
- 先CPU烘焙，但显示从一开始就避免只依赖兼容模式的GL状态假设；尽早在Angelica OpenGL下验证最小纹理quad。完整GPU烘焙后置。

## 7. 同步模型：合并上游源码，选择性接入平台能力

master作为纯上游参考：先fetch upstream，把upstream/master快进到本地master，再在临时sync分支合并到port。origin是自己的fork，不能把fork旧master误当最新上游。执行前检查工作区，不自动切换/覆盖未提交工作。

- 规范源随merge整体跟进，避免长期靠逐个cherry-pick维持一份“精选算法副本”。
- “选择性同步”指新增游戏能力/平台接口是否接入旧版，而不是悄悄漏掉上游源码修复。暂不接入能力必须明确登记。
- 冲突只应主要出现在小接缝；上游新增依赖在选源/契约审计中暴露，不能静默落到旧版不完整实现。
- 每次同步输出：旧/新上游SHA、共享文件变化、替换文件上游变化、接口变化、格式变化、未接入能力和验证结果。
- 新增现代API不强迫旧版实现；但数据格式或算法语义变化必须审查。不能用“未改legacy文件”或“merge无冲突”证明旧版未受影响。

## 8. 测试门禁与成本衡量

### 静态/构建

- 文件选择来源可追溯，缺失输入/重复FQN报错。审计全部类型引用，防现代MC与错误LWJGL类进入旧版。
- 预处理两次输出一致、无活动控制标记残留；文本补丁如确有需要，必须精确匹配且单独登记，不允许fuzz或静默跳过。默认不使用大范围源码补丁。
- 共享规范源/接缝变化必须同时编译26.3和1.20.1 Forge，并运行旧版编译；仅GTNH私有实现变化可以跳过现代编译。游戏回归按影响范围选择，不要求每次运行全矩阵。发布包重混淆、资源、依赖、Java8有效基础类检查独立执行。

### 契约fixture

同一语义场景分别由现代/旧版采集器生成契约数据；只有对齐后的输入才要求像素对照，不对不同版本自然世界要求逐像素一致。

- 坐标：四向旋转、负坐标、section/瓦片边缘、半开高度上下界。
- 数据：空气与缺失区块、单一section、超过256外观、光照、biome染色、邻接边界、玻璃/水/台阶/楼梯。
- 算法：同输入CPU瓦片、增量patch与整瓦片等价、mip/coverage、缓存/文件roundtrip、未知区域合并不擦除。
- 生命周期：外观重载及换世界后的旧任务不得提交；同名存档/服务器/维度不串图。
- Java8/25：可加载、无链接错误、相同fixture结果；线程发布与关闭flush单独测试。

### 游戏与性能

保留现有游戏用例意图，分别实现现代/旧版控制适配。默认/OptiFine/Angelica分别运行，性能测量同GPU串行、固定画质、预热并报告分布；构建通过不代替游戏通过。

跟踪：本地修改的上游文件数、修改行数、整文件算法副本数、每次升级需手工处理的文件数、接口漂移复核数、同步验证时长。数值用于比较设计，不先许诺固定工时。

在CPU闭环完成时进行第一次真实上游同步演练。若每个算法修复仍需手工搬运到legacy，说明边界设计失败，应先修边界而非继续扩功能。

## 9. 首批实施顺序

当前命令和支持边界见[构建说明](../gtnh/README.md)，固定图手测见[诊断步骤](gtnh-display-smoke.md)。以下为总体顺序；独立构建已实现，兼容探针仍有失败项。进度日记和本机运行证据不作为仓库依赖。

1. 建source-policy和选源审计任务：列清文件/依赖/替换来源；先不启动游戏。对目前基线记录hash，不直接作为完整构建白名单。
2. 锁GTNH模板/插件/JDK；做最小发布包启动与Java8降级探针、默认/Angelica最小纹理显示。此步只验证工具和显示边界。
3. 独立小提交：下界fog查询、Facing/位置包装、Looks填充provider、VoxelReader section入口。每个改动先证明现代行为未变。
4. 接旧版快照/外观提供者；复用TileBaker输出一个可对照瓦片，再接HUD、调度、落盘/重开。
5. 做上游同步演练和成本复盘，然后扩展其余现有功能；GPU烘焙与整包兼容不提前压进闭环。

暂不决定：GTNH工具链具体版本、预处理共享源最终节点、接口的最终Java签名、multi-release产物、存档扫描/共享能力恢复顺序。这些均需小实验的数据，不用设计文档替代验证。

## 10. 本轮源码审计证据

- [外观数组与现代规则混合](../src/main/java/dev/diorama/bake/Looks.java#L65-L103)：数组可作为接缝，但work/colours仍接现代BlockState；不能把整个类当成中立DTO。
- [读取器的现代palette依赖](../src/main/java/dev/diorama/bake/VoxelReader.java#L3-L25)：仅替换快照还不足以复用读取器。
- [追踪器中的完全限定现代biome引用](../src/main/java/dev/diorama/bake/TileBaker.java#L342-L370)：说明只扫描import会漏掉耦合。
- [Facing同时包含数学与游戏类型](../src/main/java/dev/diorama/Facing.java#L125-L140)：需要小切口，而不是第二套旋转数学。
- [MapTexture已有窄契约](../src/main/java/dev/diorama/render/gpu/MapTexture.java#L8-L38)与[MapCanvas尚未隔离的游戏参数](../src/main/java/dev/diorama/render/gpu/MapCanvas.java#L3-L18)。
- [GpuMesher的native内存与Cleaner](../src/main/java/dev/diorama/bake/GpuMesher.java#L73-L94)：无MC import不代表已摆脱平台/Java能力要求。
