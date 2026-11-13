# GTNH UI：ModularUI2 评估

日期：2026-11-13。结论为源码审计，不是游戏/渲染兼容验收；本轮不新增依赖、不迁移界面、不改变产品行为。

## 现有 UI 是谁实现的？

Diorama 自己实现界面、地图交互和绘制，使用 Minecraft 原生 GUI/HUD API，并非从零写所有按钮，也不是基于 ModularUI2、Cloth 或 YACL 的界面：

- [MapScreen](../src/main/java/dev/diorama/MapScreen.java#L32-L72) 继承原生 Screen；地图投影、跟随、缩放、深度、路点等行为也在模组内。
- [SettingsScreen](../src/main/java/dev/diorama/SettingsScreen.java#L12-L57) 使用原生按钮、checkbox、slider 和 tabs，绑定现有设置并复用小地图预览。
- [路点列表](../src/main/java/dev/diorama/waypoint/WaypointListScreen.java#L536-L617) 有自有行绘制与交互；[MinimapHud](../src/main/java/dev/diorama/MinimapHud.java#L41-L106) 自己处理 HUD 挂接和地图行为。
- [MapCanvas](../src/main/java/dev/diorama/render/gpu/MapCanvas.java#L7-L28) 是地图自有绘制契约，不是通用表单控件。目前仍泄漏现代 MC 类型，需要窄平台适配，不能说原文件已经可直接编入 1.7.10。
- 存在可选 [Mod Menu 配置入口](../src/main/java/dev/diorama/fabric/ModMenuCompat.java#L3-L11)，它仅打开模组自己的设置页；“未依赖上述 UI 框架”不等于“没有第三方 GUI 集成”。
- GTNH 当前只有自写的 [GuiScreen 诊断图](../gtnh/src/main/java/dev/diorama/gtnh/client/DisplayProbeScreen.java#L15-L16)，还没有功能性地图/设置/路点 UI。

## 评估基线

用户指定的 [ModularUI2 仓库](https://github.com/GTNewHorizons/ModularUI2) 已克隆到忽略的本地参考区。可复核来源不依赖该目录：

- 审计当前 HEAD：[85295c5](https://github.com/GTNewHorizons/ModularUI2/tree/85295c526231b3c44961b4db691384a39564297f)。
- 同时审计发布标签 [2.3.91-1.7.10 / 40fdbda](https://github.com/GTNewHorizons/ModularUI2/tree/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b)。作为固定发布 API 对照基线，不等于所有 GTNH 发行版本都自带同一版，也不代表本轮已启动验证。MUI1 与 MUI2 是不同的模组，不能互换 API。
- 下表以该发布标签为依据；未来若采用，应固定经审核的版本，不能照抄 HEAD 新 API。例如 HEAD 的 [SliderWidget 新滚轮接口](https://github.com/GTNewHorizons/ModularUI2/blob/85295c526231b3c44961b4db691384a39564297f/src/main/java/com/cleanroommc/modularui/widgets/SliderWidget.java#L298-L349) 并不存在于所审发布标签。

## 能帮助什么，不能替代什么？

| 区域 | 判断 | 原因 |
| --- | --- | --- |
| 设置页、路点编辑表单 | 值得作为候选薄壳 | 布局、按钮/开关/文本校验/滑条、getter/setter 值绑定能减少旧版 GUI 维护；业务配置与存储仍共享。 |
| 路点列表 | 值得原型验证 | 有滚动布局，但不能仅凭 ListWidget 就认定已做大列表虚拟化；过滤、选择、删除等产品行为仍由 Diorama 提供。 |
| 地图/设置中的地图预览 | 可以嵌入自绘适配器 | IDrawable/Widget 能承载画布，但不提供瓦片上传、LOD、拾取、光照或雾，更不替代资源生命周期。 |
| 全屏地图交互 | 不建议为统一框架整份重写 | 投影、输入状态机、跟随和层切换仍要保留一份共享语义，另养 MUI 版千行屏幕会增加同步成本。 |
| 游戏内小地图 HUD | 不能据 GUI overlay 直接接管 | OverlayStack 只在非空 GuiScreen 上建立覆盖层，不等价于没有打开界面时的 HUD。 |

源码入口：[客户端独立屏幕](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/factory/ClientGUI.java#L18-L46)、[普通 GuiScreen wrapper](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/factory/GuiManager.java#L135-L151)、[自绘接口](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/api/drawable/IDrawable.java#L35-L60)、[输入接口](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/api/widget/Interactable.java#L21-L111)、[列表](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/widgets/ListWidget.java#L89-L130)、[GUI overlay 前提](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/overlay/OverlayStack.java#L142-L165)。客户端设置页不必绑定 TE/container 或人为引入服务端同步。

## 集成成本和风险

- **裁剪与 GL 责任不能外包**：[Stencil](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/drawable/Stencil.java#L15-L40) 会清空 stencil buffer；[Platform](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/utils/Platform.java#L67-L109) 恢复默认 GUI 状态，不是调用前完整状态。嵌套地图画布需明定坐标、裁剪、FBO/GL 状态与纹理所有权，不能直接移除现有守卫。
- **lwjgl3ify 是输入兼容，不是渲染后端升级**：[接口可选标记与输入转发](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/cleanroommc/modularui/api/IMuiScreen.java#L32-L39)。所审代码未发现 Angelica 专属依赖；依旧使用旧 Tessellator/GL11，不据此承诺 Angelica/OptiFine/shader 兼容。
- **依赖应显式固定**：[依赖声明](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/dependencies.gradle#L31-L51) 包括 GTNHLib、NEI 等，并有若干 compileOnly API。Gradle 发布配置与 Forge required-after 不同；整合包已装 MUI2 不等于 Diorama 已声明依赖。不能为一个设置页盲目引入整个开发依赖集或升级用户实例。
- **许可证**：主库 [LGPL v3](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/LICENSE#L1-L25)，内嵌 EvalEx [Apache 2.0](https://github.com/GTNewHorizons/ModularUI2/blob/40fdbdabab7767999b84fda0dc5e9ff47e7edb0b/src/main/java/com/ezylang/evalex/LICENSE#L1-L5)。优先调用 API，不把库源码复制进 MIT 的共享算法树；分发时另审相关声明及组合工作义务。

## 建议

先完成真实 CPU 地图最小闭环，再以固定发布版做小范围设置/路点薄壳原型，比较省下的布局代码与新增桥接/依赖成本。保留 [移植设计](gtnh-port-design.md#L49-L65) 的“一份规范行为 + 窄平台实现”原则；不为了 MUI2 另造一份设置/路点模型或复制地图引擎。

原型需验证 Esc/父屏幕、非暂停语义、缩放、滚轮与地图拖拽消费、列表规模、离散滑条保存时机、本地化、地图预览、stencil 嵌套、GL 恢复、资源重载及 NEI 遮挡。按钮行为、默认值与名称仍按仓库要求先参考 Xaero，JourneyMap 次之；选 UI 库本身不授权改变这些产品行为。
