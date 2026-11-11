import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.8.1-gtnh.3-dev"
extra["modVersion"] = version.toString()

// Only these canonical files participate. No generated modern-version cache.
val sharedRoot = layout.projectDirectory.dir("../src/main/java")
val policyFile = layout.projectDirectory.file("source-policy.json").asFile
@Suppress("UNCHECKED_CAST")
val policy = JsonSlurper().parse(policyFile) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val sharedEntries = policy["shared"] as List<Map<String, String>>
@Suppress("UNCHECKED_CAST")
val platformEntries = policy["platform"] as List<Map<String, String>>
val sharedSources = sharedEntries.map { it.getValue("path") }
sourceSets.named("main") {
    java.srcDir(sharedRoot)
    java.include(platformEntries.map { it.getValue("path") })
    java.include(sharedSources)
}

// Offline probes have no Minecraft classpath and are never included in the mod jar.
val probe = sourceSets.create("probe") {
    java.srcDir(sharedRoot)
    java.srcDir("src/main/java")
    java.include("dev/diorama/gtnh/probe/CompatibilityProbe.java", "dev/diorama/gtnh/probe/DisplayPixelProbe.java", "dev/diorama/gtnh/probe/GeometryProbe.java",
        "dev/diorama/gtnh/diagnostic/ProbePixels.java", "dev/diorama/bake/AppearanceProbe.java")
    java.include(sharedSources)
}
tasks.named<JavaCompile>(probe.compileJavaTaskName) {
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    options.release = 25
}
val probeApi = configurations.create("probeApi")
dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
    add(probe.compileOnlyConfigurationName, "org.jspecify:jspecify:1.0.0")
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
        classpath = files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi)
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

val repositoryRoot = layout.projectDirectory.dir("..").asFile
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
                require(hash == entry.getValue("reviewedSha256")) { "Shared input changed; review source-policy: $source" }
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
    val report = layout.buildDirectory.file("reports/artifact-audit.json")
    outputs.file(report)
    doLast {
        val jarFile = artifact.get().asFile
        val classes = mutableMapOf<String, Int>()
        ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.size == names.toSet().size) { "Duplicate archive entry" }
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
            names.filter { it.endsWith(".class") }.forEach { name ->
                val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                require(bytes.size >= 8 && bytes.take(4) == listOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                val major = ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
                classes[name] = major
                if (!name.startsWith("META-INF/versions/")) require(major == 52) { "Non-Java8 base class: $name=$major" }
            }
            val expectedBase = (sharedEntries + platformEntries).map { it.getValue("fqn").replace('.', '/') + ".class" }.toSet()
            // Explicit reviewed javac product: nested patch record (no enum switch helper emitted).
            val expectedNested = setOf("dev/diorama/bake/TileImage\$Patch.class",
                "dev/diorama/bake/Looks\$StatesRenumbered.class", "dev/diorama/bake/ShapeFaces\$Pitch.class")
            require(classes.keys.filterNot { it.startsWith("META-INF/versions/") }.toSet() == expectedBase + expectedNested) {
                "Unexpected scaffold base classes: ${classes.keys}"
            }
            val expectedMr = setOf("dev/diorama/MapStyle.class", "dev/diorama/bake/TileKey.class",
                "dev/diorama/bake/TileImage.class", "dev/diorama/bake/TileImage\$Patch.class", "dev/diorama/bake/ShapeFaces\$Pitch.class",
                "dev/diorama/bake/Looks.class", "dev/diorama/bake/Looks\$StatesRenumbered.class", "dev/diorama/bake/ShapeFaces.class")
                .map { "META-INF/versions/21/$it" }.toSet()
            require(classes.keys.filter { it.startsWith("META-INF/versions/") }.toSet() == expectedMr) { "Unexpected MR classes" }
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
    classpath = files(downgradeProbeJar.flatMap { it.archiveFile }, runtimeApiFiles, runtime135Support)
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
        classpath = if (javaVersion == 8) files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi) else probe.runtimeClasspath
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
    "appearance" to "dev.diorama.bake.AppearanceProbe"
)) {
    for (variant in coreProbeVariants) {
        tasks.register<JavaExec>("$prefix$variant") {
            group = "verification"
            val native = variant == "Native25"
            if (!native) dependsOn(downgradeProbeJar)
            javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(if (variant == "Java8") 8 else 25) }
            classpath = when (variant) {
                "Native25" -> probe.runtimeClasspath
                "Runtime135Java25" -> files(downgradeProbeJar.flatMap { it.archiveFile }, runtimeApiFiles, runtime135Support)
                else -> files(downgradeProbeJar.flatMap { it.archiveFile }, probeApi)
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
