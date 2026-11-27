import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.jar.Manifest
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.8.1-preview.1"
extra["modVersion"] = version.toString()

// Only these canonical files participate. No generated modern-version cache.
val sharedRoot = layout.projectDirectory.dir("../../src/main/java")
val policyFile = layout.projectDirectory.file("source-policy.json").asFile
@Suppress("UNCHECKED_CAST")
val policy = JsonSlurper().parse(policyFile) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val sharedEntries = policy["shared"] as List<Map<String, String>>
@Suppress("UNCHECKED_CAST")
val platformEntries = policy["platform"] as List<Map<String, String>>
val sharedSources = sharedEntries.map { it.getValue("path") }
// Filter each root separately: platform adapters may deliberately share a canonical FQN.
val canonicalJava = objects.sourceDirectorySet("canonicalShared", "Selected canonical Java").apply {
    srcDir(sharedRoot)
    include(sharedSources)
}
val platformJava = objects.sourceDirectorySet("legacyPlatform", "Selected legacy Java").apply {
    srcDir("src/main/java")
    include(platformEntries.map { it.getValue("path") })
}
sourceSets.named("main") {
    java.setSrcDirs(emptyList<String>())
    java.source(canonicalJava)
    java.source(platformJava)
}

// Offline probes have no Minecraft classpath and are never included in the mod jar.
val probe = sourceSets.create("probe") {
    java.setSrcDirs(emptyList<String>())
    java.source(canonicalJava)
    java.source(objects.sourceDirectorySet("offlineAdapters", "Game-free legacy adapters").apply {
        srcDir("src/main/java")
        include("dev/diorama/gtnh/diagnostic/ProbePixels.java", "dev/diorama/data/ChunkSnapshot.java",
            "dev/diorama/bake/VoxelReader.java", "dev/diorama/bake/BiomeTints.java",
            "dev/diorama/bake/BakeDiagnostics.java", "dev/diorama/bake/SlimeChunks.java",
            "dev/diorama/gtnh/capture/ChunkCapture.java", "dev/diorama/bake/PreparedBlockLooks.java",
            "dev/diorama/gtnh/capture/OpaquePng.java", "dev/diorama/gtnh/capture/EpochFence.java",
            "dev/diorama/gtnh/capture/StaticCubeAppearance.java", "dev/diorama/gtnh/capture/LegacyGeometryMath.java", "dev/diorama/gtnh/capture/TerrainMetrics.java",
            "dev/diorama/gtnh/capture/PreviewOverview.java", "dev/diorama/gtnh/capture/PreviewTileStore.java", "dev/diorama/gtnh/capture/MapDimensionIdentity.java", "dev/diorama/gtnh/capture/RemoteWorldIdentity.java",
            "dev/diorama/gtnh/client/TerrainPreviewFrame.java", "dev/diorama/gtnh/client/TerrainPreviewRefresh.java",
            "dev/diorama/gtnh/client/LegacyWaypointBook.java", "dev/diorama/gtnh/client/MinimapOptions.java", "dev/diorama/gtnh/client/MinimapViewport.java", "dev/diorama/gtnh/client/MapViewportController.java", "dev/diorama/gtnh/client/SharedTileIo.java", "dev/diorama/gtnh/client/DirtyTerrainTiles.java", "dev/diorama/gtnh/client/HudTexturePatchPixels.java",
            "dev/diorama/gtnh/capture/SectionBlocks.java", "dev/diorama/gtnh/capture/TintCapture.java",
            "dev/diorama/gtnh/capture/LegacyLeaves.java", "dev/diorama/gtnh/capture/GrassContext.java", "dev/diorama/gtnh/capture/WaterPng.java",
            "dev/diorama/gtnh/capture/TerrainPreview.java", "dev/diorama/gtnh/client/TerrainPreviewJob.java", "dev/diorama/gtnh/client/PreviewUpload.java")
    })
    java.source(objects.sourceDirectorySet("offlineFixtures", "Selected offline fixtures").apply {
        srcDir("src/probe/java")
        include("dev/diorama/gtnh/probe/CompatibilityProbe.java", "dev/diorama/gtnh/probe/DisplayPixelProbe.java", "dev/diorama/gtnh/probe/GeometryProbe.java",
            "dev/diorama/bake/AppearanceProbe.java", "dev/diorama/bake/SectionProbe.java", "dev/diorama/bake/TintBlendProbe.java",
            "dev/diorama/bake/VoxelReaderProbe.java", "dev/diorama/bake/TerrainProbe.java", "dev/diorama/bake/BakeUtilityProbe.java",
            "dev/diorama/gtnh/probe/CaptureProbe.java", "dev/diorama/bake/SpriteAverageProbe.java",
            "dev/diorama/bake/PreparedBlockLooksProbe.java", "dev/diorama/gtnh/probe/OpaquePngProbe.java",
            "dev/diorama/gtnh/probe/StaticCubeAppearanceProbe.java", "dev/diorama/bake/OverworldAppearanceProbe.java",
            "dev/diorama/gtnh/probe/PreviewStorageProbe.java", "dev/diorama/gtnh/capture/TerrainMetricsProbe.java",
            "dev/diorama/gtnh/probe/EpochFenceProbe.java", "dev/diorama/gtnh/probe/SectionBlocksProbe.java", "dev/diorama/gtnh/probe/TintCaptureProbe.java", "dev/diorama/bake/GrassProbe.java", "dev/diorama/bake/WaterProbe.java",
            "dev/diorama/bake/TerrainPreviewProbe.java", "dev/diorama/gtnh/client/TerrainPreviewJobProbe.java", "dev/diorama/gtnh/client/PreviewUploadProbe.java",
            "dev/diorama/gtnh/client/TerrainPreviewRefreshProbe.java", "dev/diorama/gtnh/client/LegacyWaypointBookProbe.java", "dev/diorama/gtnh/client/MinimapViewportProbe.java", "dev/diorama/gtnh/client/MapViewportControllerProbe.java", "dev/diorama/gtnh/client/SharedTileIoProbe.java", "dev/diorama/gtnh/client/DirtyTerrainTilesProbe.java", "dev/diorama/gtnh/client/HudTexturePatchPixelsProbe.java")
    })
}
tasks.named<JavaCompile>(probe.compileJavaTaskName) {
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    options.release = 25
}
val probeApi = configurations.create("probeApi")
dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
    add(probe.compileOnlyConfigurationName, "org.jspecify:jspecify:1.0.0")
    // Same primitive map library requested by pinned GTNHLib; no Minecraft or Forge on this classpath.
    add(probe.implementationConfigurationName, "it.unimi.dsi:fastutil:8.5.18")
    add(probeApi.name, "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1:downgraded-8")
}
val probeJar = tasks.register<Jar>("probeJar") {
    archiveClassifier.set("probe-predowngrade")
    from(probe.output)
}
val downgradeProbeJar = tasks.register<DowngradeJar>("downgradeProbeJar") {
    inputFile.set(probeJar.flatMap { it.archiveFile })
    classpath = probe.compileClasspath
    downgradeTo.set(JavaVersion.VERSION_1_8)
    archiveClassifier.set("probe-target8")
    multiReleaseOriginal.set(false)
    multiReleaseVersions.set(emptySet<JavaVersion>())
}
fun JavaExec.recordProbeResult(id: String) {
    val captured = ByteArrayOutputStream()
    standardOutput = captured
    isIgnoreExitValue = true
    doFirst { captured.reset() }
    doLast {
        val text = captured.toString("UTF-8")
        logger.lifecycle(text.trim())
        require(executionResult.get().exitValue == 0) { "Compatibility probe $id failed; see CASE-FAIL diagnostics above" }
        val digest = Regex("FIXTURE-SHA256=([0-9a-f]{64})").find(text)?.groupValues?.get(1)
            ?: error("Probe did not emit its deterministic fixture digest")
        layout.buildDirectory.file("reports/probe-$id.sha256").get().asFile.apply {
            parentFile.mkdirs(); writeText(digest + "\n")
        }
    }
}
for (javaVersion in listOf(8, 25)) {
    tasks.register<JavaExec>("probeJava$javaVersion") {
        group = "verification"
        description = "Run the target8 offline probe on a real Java $javaVersion runtime."
        recordProbeResult("target8-on-$javaVersion")
        systemProperty("diorama.probe.strictRecordText", providers.gradleProperty("strictRecordText").getOrElse("true"))
        dependsOn(downgradeProbeJar)
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        classpath = files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi, configurations[probe.runtimeClasspathConfigurationName])
        mainClass.set("dev.diorama.gtnh.probe.CompatibilityProbe")
    }
}
tasks.register<JavaExec>("probeNative25") {
    group = "verification"
    recordProbeResult("native25")
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    classpath = probe.runtimeClasspath
    mainClass.set("dev.diorama.gtnh.probe.CompatibilityProbe")
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

val repositoryRoot = layout.projectDirectory.dir("../..").asFile.canonicalFile
val upstreamLicense = repositoryRoot.resolve("LICENSE")
tasks.named<Jar>("jar") {
    from(upstreamLicense) { rename { "LICENSE_diorama" } }
}
val gitHead = providers.exec {
    workingDir(repositoryRoot)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText
val gitStatus = providers.exec {
    workingDir(repositoryRoot)
    commandLine("git", "status", "--porcelain=v1", "--untracked-files=all")
}.standardOutput.asText
val auditSources = tasks.register("auditSources") {
    group = "verification"
    description = "Check explicit selection and emit current source provenance."
    val report = layout.buildDirectory.file("reports/source-manifest.json")
    outputs.file(report)
    // Git/dirty provenance is intentionally refreshed on every invocation.
    outputs.upToDateWhen { false }
    doLast {
        val origins = sharedEntries.map { Triple(it, sharedRoot.asFile, "shared") } +
            platformEntries.map { Triple(it, file("src/main/java"), "platform") }
        val fqns = mutableSetOf<String>()
        val rows = origins.map { (entry, root, kind) ->
            val relative = entry.getValue("path")
            require(!relative.contains("..") && relative.endsWith(".java")) { "Invalid selected path: $relative" }
            val source = root.resolve(relative)
            require(source.isFile) { "Missing source: $source" }
            val text = source.readText()
            val packageName = Regex("(?m)^package ([a-zA-Z0-9_.]+);").find(text)?.groupValues?.get(1)
                ?: error("Missing package: $source")
            val fqn = "$packageName.${source.nameWithoutExtension}"
            require(fqn == entry.getValue("fqn") && fqns.add(fqn)) { "Wrong/duplicate FQN: $fqn" }
            val hash = sha256(source.readBytes())
            if (kind == "shared") {
                val canonicalLfHash = sha256(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                require(canonicalLfHash == entry.getValue("reviewedSha256")) { "Shared input changed; review source-policy: $source" }
            }
            mapOf("fqn" to fqn, "kind" to kind, "path" to source.relativeTo(repositoryRoot).invariantSeparatorsPath, "sha256" to hash)
        }
        val expected = origins.map { (entry, root, _) -> root.resolve(entry.getValue("path")).canonicalFile }.toSet()
        val actual = sourceSets["main"].allJava.files.map { it.canonicalFile }.toSet()
        require(actual == expected) { "Unexpected/missing main inputs: actual=$actual expected=$expected" }
        val configInputs = fileTree(projectDir) {
            include("*.gradle*", "*.properties", "source-policy.json", "gradlew*", "gradle/**", "src/**")
        }.files.sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }.map {
            mapOf("path" to it.relativeTo(repositoryRoot).invariantSeparatorsPath, "sha256" to sha256(it.readBytes()))
        }
        val manifest = mapOf(
            "schemaVersion" to 1, "commit" to gitHead.get().trim(),
            "dirtyStatusSha256" to sha256(gitStatus.get().toByteArray(Charsets.UTF_8)),
            "inputDigest" to sha256(JsonOutput.toJson(rows + configInputs).toByteArray(Charsets.UTF_8)),
            "policy" to policy, "sources" to rows, "configurationAndProbeInputs" to configInputs,
            "scope" to "Selected top-level FQN origins; javac validates nested definitions and dependencies."
        )
        report.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n") }
    }
}
tasks.named("compileJava") { dependsOn(auditSources) }
val verifyArtifact = tasks.register("verifyArtifact") {
    group = "verification"
    description = "Audit the reobfuscated scaffold, not a dev or probe artifact."
    dependsOn("reobfJar")
    val artifact = tasks.named<ReobfuscatedJar>("reobfJar").flatMap { it.archiveFile }
    inputs.file(artifact)
    inputs.file(policyFile)
    inputs.file(upstreamLicense)
    val report = layout.buildDirectory.file("reports/artifact-audit.json")
    outputs.file(report)
    doLast {
        val jarFile = artifact.get().asFile
        val classes = mutableMapOf<String, Int>()
        ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.size == names.toSet().size) { "Duplicate archive entry" }
            val license = requireNotNull(zip.getEntry("LICENSE_diorama")) { "Missing Diorama license" }
            require(zip.getInputStream(license).use { it.readBytes() }.contentEquals(upstreamLicense.readBytes())) {
                "Diorama license differs from the repository LICENSE"
            }
            require("mcmod.info" in names) { "Missing legacy mod metadata" }
            @Suppress("UNCHECKED_CAST")
            val metadata = JsonSlurper().parse(zip.getInputStream(zip.getEntry("mcmod.info"))) as List<Map<String, Any>>
            require(metadata.single()["modid"] == "diorama" && metadata.single()["version"] == project.version.toString()) {
                "Legacy metadata identity/version drift"
            }
            require(names.none { it.contains("/probe/") || it.startsWith("net/minecraft/") ||
                it.endsWith(".mixins.json") || it == "fabric.mod.json" || it == "META-INF/mods.toml" }) {
                "Unexpected probe, embedded game or modern metadata"
            }
            val manifest = Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))).mainAttributes
            require(manifest.getValue("FMLCorePlugin") == "dev.diorama.gtnh.loading.DioramaEarlyMixins" &&
                manifest.getValue("MixinConfigs") == "mixins.diorama.json" &&
                manifest.getValue("TweakClass") == "org.spongepowered.asm.launch.MixinTweaker" &&
                manifest.getValue("FMLCorePluginContainsFMLMod") == "true") { "Early mixin bootstrap manifest drift" }
            require(names.filter { it.startsWith("mixins.") && it.endsWith(".json") }.toSet() ==
                setOf("mixins.diorama.json", "mixins.diorama.refmap.json")) { "Unexpected mixin assets" }
            @Suppress("UNCHECKED_CAST")
            val mixinConfig = JsonSlurper().parse(zip.getInputStream(zip.getEntry("mixins.diorama.json"))) as Map<String, Any>
            require(mixinConfig == mapOf("required" to true, "minVersion" to "0.8.5-GTNH",
                "package" to "dev.diorama.gtnh.mixins", "refmap" to "mixins.diorama.refmap.json",
                "compatibilityLevel" to "JAVA_8", "mixins" to emptyList<String>(),
                "client" to listOf("client.ResourceReloadMixin", "client.BlockAtlasMixin", "client.ChunkMutationMixin", "client.RenderBlocksCaptureMixin", "client.TessellatorCaptureMixin", "client.HealthPacketMixin"),
                "server" to emptyList<String>(), "injectors" to mapOf("defaultRequire" to 1))) { "Client hook configuration drift" }
            @Suppress("UNCHECKED_CAST")
            val refmap = JsonSlurper().parse(zip.getInputStream(zip.getEntry("mixins.diorama.refmap.json"))) as Map<String, Any>
            val expectedMappings = mapOf(
                "dev/diorama/gtnh/mixins/client/ResourceReloadMixin" to mapOf(
                    "reloadResources(Ljava/util/List;)V" to "Lnet/minecraft/client/resources/SimpleReloadableResourceManager;func_110541_a(Ljava/util/List;)V",
                    "reloadResourcePack(Lnet/minecraft/client/resources/IResourcePack;)V" to "Lnet/minecraft/client/resources/SimpleReloadableResourceManager;func_110545_a(Lnet/minecraft/client/resources/IResourcePack;)V"),
                "dev/diorama/gtnh/mixins/client/BlockAtlasMixin" to mapOf(
                    "loadTexture(Lnet/minecraft/client/resources/IResourceManager;)V" to "Lnet/minecraft/client/renderer/texture/TextureMap;func_110551_a(Lnet/minecraft/client/resources/IResourceManager;)V",
                    "loadTextureAtlas(Lnet/minecraft/client/resources/IResourceManager;)V" to "Lnet/minecraft/client/renderer/texture/TextureMap;func_110571_b(Lnet/minecraft/client/resources/IResourceManager;)V"),
                "dev/diorama/gtnh/mixins/client/RenderBlocksCaptureMixin" to mapOf(
                    "renderBlockByRenderType(Lnet/minecraft/block/Block;III)Z" to "Lnet/minecraft/client/renderer/RenderBlocks;func_147805_b(Lnet/minecraft/block/Block;III)Z"),
                "dev/diorama/gtnh/mixins/client/TessellatorCaptureMixin" to mapOf(
                    "addVertexWithUV(DDDDD)V" to "Lnet/minecraft/client/renderer/Tessellator;func_78374_a(DDDDD)V",
                    "setTextureUV(DD)V" to "Lnet/minecraft/client/renderer/Tessellator;func_78385_a(DD)V",
                    "addVertex(DDD)V" to "Lnet/minecraft/client/renderer/Tessellator;func_78377_a(DDD)V"),
                "dev/diorama/gtnh/mixins/client/HealthPacketMixin" to mapOf(
                    "handleUpdateHealth(Lnet/minecraft/network/play/server/S06PacketUpdateHealth;)V" to "Lnet/minecraft/client/network/NetHandlerPlayClient;func_147249_a(Lnet/minecraft/network/play/server/S06PacketUpdateHealth;)V"),
                "dev/diorama/gtnh/mixins/client/ChunkMutationMixin" to mapOf(
                    "setBlockMetadata(IIII)Z" to "Lnet/minecraft/world/chunk/Chunk;func_76589_b(IIII)Z",
                    "fillChunk([BIIZ)V" to "Lnet/minecraft/world/chunk/Chunk;func_76607_a([BIIZ)V",
                    "onChunkUnload()V" to "Lnet/minecraft/world/chunk/Chunk;func_76623_d()V"))
            require(refmap == mapOf("mappings" to expectedMappings, "data" to mapOf("searge" to expectedMappings))) {
                "Production SRG hook target refmap drift"
            }
            names.filter { it.endsWith(".class") }.forEach { name ->
                val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                require(bytes.size >= 8 && bytes.take(4) == listOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                val major = ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
                classes[name] = major
                if (!name.startsWith("META-INF/versions/")) require(major == 52) { "Non-Java8 base class: $name=$major" }
            }
            val expectedBase = (sharedEntries + platformEntries).map { it.getValue("fqn").replace('.', '/') + ".class" }.toSet()
            // Explicit reviewed nested products; no catch-all allowance for extra classes.
            val expectedNested = setOf("dev/diorama/gtnh/client/LegacyWaypointBook\$Point.class",
                "dev/diorama/gtnh/capture/TerrainPreview\$Work.class",
                "dev/diorama/gtnh/client/SharedTileIo\$Read.class",
                "dev/diorama/gtnh/client/SharedTileIo\$Request.class",
                "dev/diorama/gtnh/client/MapViewportController\$Tile.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Aggregate.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Counter.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Phase.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$FrameView.class",
                "dev/diorama/gtnh/client/TerrainWindow\$1.class",
                "dev/diorama/gtnh/capture/PreviewTileStore\$PixelStream.class",
                "dev/diorama/gtnh/client/ClientMinimap\$CachedTile.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Axis.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Bounds.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Heading.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Tile.class",
                "dev/diorama/gtnh/capture/StaticCubeAppearance\$Access.class",
                "dev/diorama/gtnh/client/ClientBlockAppearance\$1.class",
                "dev/diorama/gtnh/capture/GrassContext\$Source.class",
                "dev/diorama/bake/TileImage\$Patch.class",
                "dev/diorama/gtnh/capture/TintCapture\$Source.class",
                "dev/diorama/gtnh/capture/TintCapture\$Prepared.class",
                "dev/diorama/gtnh/client/ClientCaptureLifecycle\$Session.class",
                "dev/diorama/bake/Looks\$StatesRenumbered.class", "dev/diorama/bake/ShapeFaces\$Pitch.class",
                "dev/diorama/bake/DecodedSections\$Air.class", "dev/diorama/bake/DecodedSections\$Section.class",
                "dev/diorama/bake/DecodedSections\$PackedIndices.class", "dev/diorama/bake/TintBlend\$Source.class",
                "dev/diorama/bake/VoxelReader\$Inputs.class", "dev/diorama/bake/VoxelReader\$Decoded.class",
                "dev/diorama/data/ChunkSnapshot\$Tints.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Access.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Chunk.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Inputs.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Pending.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Result.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Section.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Stale.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Status.class",
                "dev/diorama/gtnh/client/ClientCaptureAccess\$LiveChunk.class",
                "dev/diorama/gtnh/client/ClientCaptureAccess\$LiveSection.class")
            require(classes.keys.filterNot { it.startsWith("META-INF/versions/") }.toSet() == expectedBase + expectedNested) {
                "Unexpected scaffold base classes: ${classes.keys}"
            }
            val expectedMr = setOf("dev/diorama/gtnh/capture/TerrainPreview.class",
                "dev/diorama/gtnh/client/ClientProxy.class",
                "dev/diorama/gtnh/client/LegacyGpuBaker.class",
                "dev/diorama/gtnh/client/LegacyGpuVolume.class",
                "dev/diorama/gtnh/client/MinimapOptionsScreen.class",
                "dev/diorama/gtnh/capture/MapDimensionIdentity.class",
                "dev/diorama/gtnh/client/ClientPreviewStorage.class",
                "dev/diorama/gtnh/client/SharedTerrainMapScreen.class",
                "dev/diorama/gtnh/client/ClientWaypoints.class",
                "dev/diorama/gtnh/client/LegacyWaypointBook.class",
                "dev/diorama/gtnh/client/LegacyWaypointBook\$Point.class",
                "dev/diorama/gtnh/client/WaypointEditScreen.class",
                "dev/diorama/gtnh/client/WaypointListScreen.class",
                "dev/diorama/gtnh/client/SharedTileIo.class",
                "dev/diorama/gtnh/client/SharedTileIo\$Read.class",
                "dev/diorama/gtnh/client/SharedTileIo\$Request.class",
                "dev/diorama/gtnh/client/MapViewportController.class",
                "dev/diorama/gtnh/client/MapViewportController\$Tile.class",
                "dev/diorama/gtnh/client/HudTexturePatchPixels.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Aggregate.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Counter.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$Phase.class",
                "dev/diorama/gtnh/capture/TerrainMetrics\$FrameView.class",
                "dev/diorama/gtnh/client/TerrainWindow\$1.class",
                "dev/diorama/gtnh/capture/TerrainMetrics.class",
                "dev/diorama/gtnh/client/TerrainWindow.class",
                "dev/diorama/gtnh/capture/PreviewTileStore.class",
                "dev/diorama/gtnh/capture/PreviewTileStore\$PixelStream.class",
                "dev/diorama/gtnh/client/TerrainPreviewFrame.class",
                "dev/diorama/gtnh/client/ClientMinimap.class",
                "dev/diorama/gtnh/client/MinimapViewport.class",
                "dev/diorama/gtnh/client/ClientMinimap\$CachedTile.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Axis.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Bounds.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Heading.class",
                "dev/diorama/gtnh/client/MinimapViewport\$Tile.class",
                "dev/diorama/gtnh/capture/StaticCubeAppearance.class",
                "dev/diorama/gtnh/capture/StaticCubeAppearance\$Access.class",
                "dev/diorama/gtnh/client/ClientBlockAppearance\$1.class",
                "dev/diorama/gtnh/capture/TerrainPreview\$Work.class",
                "dev/diorama/MapStyle.class", "dev/diorama/bake/TileKey.class",
                "dev/diorama/gtnh/capture/GrassContext.class",
                "dev/diorama/gtnh/capture/GrassContext\$Source.class",
                "dev/diorama/gtnh/capture/TintCapture.class",
                "dev/diorama/gtnh/capture/TintCapture\$Source.class",
                "dev/diorama/gtnh/capture/TintCapture\$Prepared.class",
                "dev/diorama/gtnh/capture/SectionBlocks.class",
                "dev/diorama/gtnh/client/ClientBlockAppearance.class",
                "dev/diorama/gtnh/client/ClientCaptureLifecycle.class",
                "dev/diorama/gtnh/client/ClientCaptureLifecycle\$Session.class",
                "dev/diorama/bake/TileImage.class", "dev/diorama/bake/TileImage\$Patch.class", "dev/diorama/bake/ShapeFaces\$Pitch.class",
                "dev/diorama/bake/Looks.class", "dev/diorama/bake/Looks\$StatesRenumbered.class", "dev/diorama/bake/ShapeFaces.class",
                "dev/diorama/bake/DecodedSections.class", "dev/diorama/bake/DecodedSections\$Air.class",
                "dev/diorama/bake/DecodedSections\$Section.class", "dev/diorama/bake/DecodedSections\$PackedIndices.class",
                "dev/diorama/bake/TintBlend.class", "dev/diorama/bake/TintBlend\$Source.class",
                "dev/diorama/bake/BakeDiagnostics.class", "dev/diorama/bake/Overlays.class", "dev/diorama/bake/TileSurface.class",
                "dev/diorama/bake/VoxelReader.class", "dev/diorama/bake/VoxelReader\$Inputs.class", "dev/diorama/bake/VoxelReader\$Decoded.class",
                "dev/diorama/data/ChunkSnapshot.class", "dev/diorama/data/ChunkSnapshot\$Tints.class",
                "dev/diorama/gtnh/capture/ChunkCapture.class",
                "dev/diorama/gtnh/client/ClientCaptureAccess.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Access.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Chunk.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Inputs.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Pending.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Result.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Section.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Stale.class",
                "dev/diorama/gtnh/capture/ChunkCapture\$Status.class",
                "dev/diorama/gtnh/client/ClientCaptureAccess\$LiveChunk.class",
                "dev/diorama/gtnh/client/ClientCaptureAccess\$LiveSection.class")
                .map { "META-INF/versions/21/$it" }.toSet()
            require(classes.keys.filter { it.startsWith("META-INF/versions/") }.toSet() == expectedMr) {
                "Unexpected MR classes: extra=${classes.keys.filter { it.startsWith("META-INF/versions/") }.toSet() - expectedMr}, missing=${expectedMr - classes.keys}"
            }
            require(expectedMr.all { classes[it] == 65 }) { "Expected Java21 MR bytecode" }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "artifact" to jarFile.name, "sha256" to sha256(jarFile.readBytes()),
                "bytes" to jarFile.length(), "classes" to classes,
                "scope" to "Selected legacy production classes only; not Forge/GL runtime validation."
            ))) + "\n")
        }
    }
}
val verifyProbeParity = tasks.register("verifyProbeParity") {
    group = "verification"
    dependsOn("probeNative25", "probeJava8", "probeJava25")
    doLast {
        val digests = listOf("native25", "target8-on-8", "target8-on-25").map {
            layout.buildDirectory.file("reports/probe-$it.sha256").get().asFile.readText().trim()
        }
        require(digests.toSet().size == 1) { "Native/downgraded fixture mismatch: $digests" }
        logger.lifecycle("Native25 / target8-on-8 / target8-on-25 fixture parity: ${digests.first()}")
    }
}
// Exercise the same transformed bytes with the API actually requested by GTNHLib on Java25.
val runtime135Api = configurations.create("runtime135Api") { isTransitive = false }
// RFB1.1.0 supplies ASM in-game. Reproduce that support explicitly, not with a Forge fat jar.
val runtime135Support = configurations.create("runtime135Support") { isTransitive = false }
dependencies {
    add(runtime135Api.name, "xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.5:downgraded-17")
    for (module in listOf("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis")) {
        add(runtime135Support.name, "org.ow2.asm:$module:9.9.1")
    }
}
val runtimeApiOverride = providers.gradleProperty("runtimeProbeApi")
val runtimeApiFiles = if (runtimeApiOverride.isPresent) files(runtimeApiOverride.get()) else runtime135Api
tasks.register<JavaExec>("probeRuntime135Java25") {
    group = "verification"
    dependsOn(downgradeProbeJar)
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    classpath = files(downgradeProbeJar.flatMap { it.archiveFile }, runtimeApiFiles, runtime135Support, configurations[probe.runtimeClasspathConfigurationName])
    mainClass.set("dev.diorama.gtnh.probe.CompatibilityProbe")
    systemProperty("diorama.probe.strictRecordText", providers.gradleProperty("strictRecordText").getOrElse("true"))
    recordProbeResult("target8-runtime135-on25")
    doFirst {
        val api = runtimeApiFiles.singleFile
        val actualHash = sha256(api.readBytes())
        require(actualHash == "e835bad6a1879e72dfc027be13da4d4b25d49bdb3bdac2ed1cb80b933c6f7c9b") {
            "Runtime API differs from the inspected GTNHLib dependency: $actualHash"
        }
        logger.lifecycle("Runtime135 API: "+api.name+" SHA256="+actualHash)
    }
}
for (javaVersion in listOf(8, 25)) {
    tasks.register<JavaExec>("displayPixelsJava$javaVersion") {
        group = "verification"
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        // Java8 runs transformed, Java25 original. Even transformed System calls need pinned API stubs.
        classpath = if (javaVersion == 8) files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi, configurations[probe.runtimeClasspathConfigurationName]) else probe.runtimeClasspath
        mainClass.set("dev.diorama.gtnh.probe.DisplayPixelProbe")
        recordProbeResult("display-pixels-$javaVersion")
    }
}
val verifyDisplayPixels = tasks.register("verifyDisplayPixels") {
    group = "verification"
    dependsOn("displayPixelsJava8", "displayPixelsJava25")
    doLast {
        val hashes = listOf(8, 25).map {
            layout.buildDirectory.file("reports/probe-display-pixels-$it.sha256").get().asFile.readText().trim()
        }
        require(hashes.toSet().size == 1) { "Pixel conversion mismatch: $hashes" }
        logger.lifecycle("Display CPU pixel contract parity: "+hashes.first()+" (GL UNTESTED)")
    }
}
tasks.register("verifyBaseline") {
    group = "verification"
    description = "Build/package and offline pixel/native25 checks; NOT a substitute for strict compatibility or game tests."
    dependsOn(auditSources, verifyArtifact, verifyDisplayPixels, "probeNative25", "spotlessCheck", "checkstyleMain", "checkstyleProbe")
}
tasks.named("check") { dependsOn(auditSources, verifyArtifact, verifyProbeParity, verifyDisplayPixels, "probeRuntime135Java25") }

// Exercise the canonical pure cores without Minecraft on the probe classpath.
val coreProbeVariants = listOf("Native25", "Java8", "Java25", "Runtime135Java25")
for ((prefix, mainName) in listOf(
    "geometry" to "dev.diorama.gtnh.probe.GeometryProbe",
    "appearance" to "dev.diorama.bake.AppearanceProbe",
    "sections" to "dev.diorama.bake.SectionProbe",
    "tints" to "dev.diorama.bake.TintBlendProbe",
    "reader" to "dev.diorama.bake.VoxelReaderProbe",
    "terrain" to "dev.diorama.bake.TerrainProbe",
    "bakeUtilities" to "dev.diorama.bake.BakeUtilityProbe",
    "capture" to "dev.diorama.gtnh.probe.CaptureProbe",
    "spriteAverage" to "dev.diorama.bake.SpriteAverageProbe",
    "preparedLooks" to "dev.diorama.bake.PreparedBlockLooksProbe",
    "opaquePng" to "dev.diorama.gtnh.probe.OpaquePngProbe",
    "epochFence" to "dev.diorama.gtnh.probe.EpochFenceProbe",
    "sectionBlocks" to "dev.diorama.gtnh.probe.SectionBlocksProbe",
    "tintCapture" to "dev.diorama.gtnh.probe.TintCaptureProbe",
    "grass" to "dev.diorama.bake.GrassProbe",
    "water" to "dev.diorama.bake.WaterProbe",
    "staticCubeAppearance" to "dev.diorama.gtnh.probe.StaticCubeAppearanceProbe",
    "overworldAppearance" to "dev.diorama.bake.OverworldAppearanceProbe",
    "previewStorage" to "dev.diorama.gtnh.probe.PreviewStorageProbe",
    "terrainMetrics" to "dev.diorama.gtnh.capture.TerrainMetricsProbe",
    "terrainPreview" to "dev.diorama.bake.TerrainPreviewProbe",
    "terrainPreviewJob" to "dev.diorama.gtnh.client.TerrainPreviewJobProbe",
    "terrainPreviewRefresh" to "dev.diorama.gtnh.client.TerrainPreviewRefreshProbe",
    "legacyWaypointBook" to "dev.diorama.gtnh.client.LegacyWaypointBookProbe",
    "minimapViewport" to "dev.diorama.gtnh.client.MinimapViewportProbe",
    "mapViewportController" to "dev.diorama.gtnh.client.MapViewportControllerProbe",
    "sharedTileIo" to "dev.diorama.gtnh.client.SharedTileIoProbe",
    "dirtyTerrainTiles" to "dev.diorama.gtnh.client.DirtyTerrainTilesProbe",
    "hudTexturePatchPixels" to "dev.diorama.gtnh.client.HudTexturePatchPixelsProbe",
    "previewUpload" to "dev.diorama.gtnh.client.PreviewUploadProbe"
)) {
    for (variant in coreProbeVariants) {
        tasks.register<JavaExec>("$prefix$variant") {
            group = "verification"
            val native = variant == "Native25"
            if (!native) dependsOn(downgradeProbeJar)
            javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(if (variant == "Java8") 8 else 25) }
            classpath = when (variant) {
                "Native25" -> probe.runtimeClasspath
                "Runtime135Java25" -> files(downgradeProbeJar.flatMap { it.archiveFile }, runtimeApiFiles, runtime135Support, configurations[probe.runtimeClasspathConfigurationName])
                else -> files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi, configurations[probe.runtimeClasspathConfigurationName])
            }
            if (variant == "Runtime135Java25") doFirst {
                require(sha256(runtimeApiFiles.singleFile.readBytes()) == "e835bad6a1879e72dfc027be13da4d4b25d49bdb3bdac2ed1cb80b933c6f7c9b")
            }
            mainClass.set(mainName)
            recordProbeResult("$prefix-$variant")
        }
    }
    val verification = tasks.register("verify" + prefix.replaceFirstChar { it.uppercase() }) {
        group = "verification"
        dependsOn(coreProbeVariants.map { "$prefix$it" })
        doLast {
            val hashes = coreProbeVariants.map {
                layout.buildDirectory.file("reports/probe-$prefix-$it.sha256").get().asFile.readText().trim()
            }
            require(hashes.toSet().size == 1) { "Shared $prefix mismatch: $hashes" }
            logger.lifecycle("Shared $prefix parity (native25, target8 on8/25, runtime135): "+hashes.first())
        }
    }
    tasks.named("verifyBaseline") { dependsOn(verification) }
    tasks.named("check") { dependsOn(verification) }
}
