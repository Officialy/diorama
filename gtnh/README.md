# Diorama 1.7.10 / GTNH

独立旧版构建；移植方向与结构见[主要计划](../docs/gtnh-port-design.md)。

## 构建

在仓库根目录执行（Windows 使用 `gradlew.bat`）：

```sh
./gtnh/gradlew -p gtnh assemble verifyBaseline
```

不启动游戏；离线检查不代替游戏验收。完整严格检查使用 `build --continue`，不以基准任务代替完整兼容结论。

## 固定输入

- JDK25、Gradle9.7.1、GTNH convention2.0.33 / settings0.2.2。
- Forge10.13.4.1614 / stable12、JVM Downgrader2.0.1、GTNHLib0.11.48；早期资源钩子依赖 UniMixins0.3.1+。
- 可选存储适配固定编译 EndlessIDs1.7.4 API（compileOnly，不随包分发）；未安装时保留原版路径。
- [构建配置](build.gradle.kts)、[依赖配置](dependencies.gradle)、[选源清单](source-policy.json) 为具体输入依据，wrapper 必须保留。

目前仍是开发中的移植，尚非可用地图产品。仅本地开发，不自动推送、部署或启动实例。过程状态和已知问题只记本地简短备忘，不在此维护逐版本进度、测试数字或接口详解。
