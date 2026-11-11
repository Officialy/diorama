# 固定图片显示探针：检查步骤

客户端命令 `/dioramaprobe`仅显示固定图片；不是地形、小地图或地图存储。基础色序/朝向/透明度已有用户人工截图确认（.1构建）；这不覆盖后续构建、压力测试或可选渲染器兼容。当前版本和命令见[构建说明](../gtnh/README.md)。

## 诊断实现

- [客户端入口](../gtnh/src/main/java/dev/diorama/gtnh/client/ClientProxy.java)等聊天框关闭后打开屏幕；换世界或出现其他界面时取消待打开请求。资源重载只增加代次，不执行GL。
- [屏幕](../gtnh/src/main/java/dev/diorama/gtnh/client/DisplayProbeScreen.java)直接拥有DynamicTexture，不注册TextureManager；绘制线程懒创建/上传，R、resize或资源代次变化重建，关闭释放并记录计数。
- [GL状态范围](../gtnh/src/main/java/dev/diorama/gtnh/client/ProbeGlState.java)仅保存/恢复实际触碰的纹理、混合、颜色和enable状态；不改FBO或shader，不是完整渲染后端。
- [像素输入](../gtnh/src/main/java/dev/diorama/gtnh/diagnostic/ProbePixels.java)与[离线断言](../gtnh/src/probe/java/dev/diorama/gtnh/probe/DisplayPixelProbe.java)覆盖颜色、alpha、黑L、ABGR→ARGB和输入不变性；不代替GL验证。

## 最小手测

1. 在专用近原版实例使用Java25、Forge1614、lwjgl3ify和必要库；无Angelica、无OptiFine、不开光影。默认资源包、新测试世界、anaglyph关闭。只安装一个Diorama生产包，不使用dev/predowngrade/probe包。
2. 输入 `/dioramaprobe`，确认聊天框关闭后出现标题含NOT a map的面板。
3. 上方左红右绿；中间左蓝右白；黑L在左上；底部红色alpha为0、64、128、255，第一段完全透出灰棋盘，最后一段完全红色。
4. 按R三次，画面应不变；Esc回世界，字体、背包和世界不能染色/变黑/丢透明度。
5. 重开面板，退出世界、回标题、正常退出。保存截图和当次日志到本机证据目录，不提交仓库。

## 生命周期补测

- 开关20次，关闭时uploads应等于releases；只是对象计数，不是显存泄漏完整证明。
- 开着面板调整窗口/全屏；关闭后改GUI scale再开，应不翻转、错位或黑图。
- 关闭面板，F3+T等待资源重载后再开，应重新上传；R仅手工重建，不冒充资源重载验证。
- 换世界后重新打开，不自动弹旧界面、不把测试像素写入存档。

## 自动检查和证据边界

从仓库根运行：

	./gtnh/gradlew -p gtnh assemble verifyBaseline

其中原生Java25、显示像素和共享几何fixture、选源/产物审计均不启动游戏。生成报告在旧目标build目录，本机运行截图/日志在忽略的本机目录。完整严格兼容门禁仍有已知record/List语义失败，见[构建说明](../gtnh/README.md#known-compatibility-failures)。基准绿灯不是完整build或Java8产品支持。

只对实际重复动作投入自动化；无稳定重复失败时不为固定图片另建桌面控制框架。可选Angelica/OptiFine、shader包、地形更新、持久化和GPU烘焙分别验证，不能用此探针替代。助手不自动部署、启动或修改用户实例。
