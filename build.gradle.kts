@file:Suppress("UnstableApiUsage")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.detekt.gradle.Detekt
import dev.kikugie.loomx.LoomCompatDependencyExtension
import dev.kikugie.loomx.LoomCompatProjectExtension
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import groovy.json.JsonSlurper
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.NestJarsAction
import net.ltgt.gradle.errorprone.errorprone
import net.ornithemc.ploceus.api.PloceusGradleExtensionApi
import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile

plugins {
    java
    `maven-publish`
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("dev.kikugie.loom-back-compat")
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT" apply false
    id("ploceus") version "1.17.4" apply false
    id("me.modmuss50.mod-publish-plugin")
    id("dev.detekt") version "2.0.0-alpha.6"
    id("net.ltgt.errorprone") version "5.1.1"
    checkstyle
}

shadow {
    addShadowJarToAssembleLifecycle = false
}

val stonecutter = extensions.getByName("stonecutter") as StonecutterBuildExtension
val loomx = extensions.getByType<LoomCompatProjectExtension>()
val mcVersion = stonecutter.current.version
val isOrnithe = mcVersion == "1.8.9"
val loader = if (isOrnithe) "ornithe" else "fabric"
val ploceus = if (isOrnithe) {
    pluginManager.apply("net.fabricmc.fabric-loom-remap")
    pluginManager.apply("ploceus")

    configurations.configureEach {
        exclude(group = "org.lwjgl.lwjgl")
    }

    extensions.getByType<PloceusGradleExtensionApi>().apply {
        setIntermediaryGeneration(2)
    }
} else {
    null
}

run {
    val (version, loader) = stonecutter.current.project.split("-", limit = 2)
    stonecutter.properties.tags(version, loader)
}

fun optionalProperty(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> findProperty(name)?.toString()?.takeIf { it.isNotBlank() } }

val minecraftPredicate = property("mod.mc_compat") as String

stonecutter constants {
    put("sodium", optionalProperty("deps.sodium") != null)
    put("sodium_config", optionalProperty("deps.sodium") != null && stonecutter.current.parsed >= "1.21.11")
    put("wwaypoints", optionalProperty("deps.wwaypoints") != null)
    put("iris", optionalProperty("deps.iris") != null)
    put("dh", optionalProperty("deps.distanthorizons") != null)
}

val fabricLoaderVersion = property("deps.fabric_loader") as String
val fabricLanguageKotlinVersion = property("deps.fabric_language_kotlin") as String
val kotlinVersion = property("deps.kotlin") as String
val ktorVersion = property("deps.ktor") as String
val sentryVersion = property("deps.sentry") as String
val mixinExtrasVersion = property("deps.mixin_extras") as String
val mixinSquaredVersion = property("deps.mixin_squared") as String
val junitVersion = property("deps.junit") as String

val ktorModules = listOf(
    "io.ktor:ktor-client-core",
    "io.ktor:ktor-client-cio",
    "io.ktor:ktor-client-content-negotiation",
    "io.ktor:ktor-serialization-kotlinx-json"
).map { "$it:$ktorVersion" }

val flkProvidedModules = setOf(
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-cbor-jvm",
    "org.jetbrains.kotlinx:kotlinx-io-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm",
)

group = property("mod.group") as String
val testerBuild = (optionalProperty("polyplus.tester") ?: System.getenv("POLYPLUS_TESTER"))?.trim().equals("true", ignoreCase = true)
version = "${property("mod.version")}+${stonecutter.current.version}${if (testerBuild) "-tester" else ""}"
base.archivesName = property("mod.id") as String
val oneconfigVersion = property("deps.oneconfig") as String

repositories {
    if (isOrnithe) {
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("org.lwjgl") }
        }
    }
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }

    mavenLocal()
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://redirector.kotlinlang.org/maven/compose-dev") {
        content { includeGroupAndSubgroups("org.jetbrains") }
    }
    maven("https://maven.cloverclient.com/releases")
    maven("https://jitpack.io") {
        content { includeGroupAndSubgroups("com.github") }
    }
    maven("https://maven.fabricmc.net/") {
        content { includeGroupAndSubgroups("net.fabricmc") }
    }
    maven("https://central.sonatype.com/repository/maven-snapshots") {
        content { includeGroup("net.kyori") }
    }
    strictMaven("https://maven.terraformersmc.com/releases/", "TerraformersMC", "com.terraformersmc")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://maven.bawnorton.com/releases", "Bawnorton", "com.github.bawnorton.mixinsquared")
    strictMaven("https://maven.maxhenkel.de/repository/public", "MaxHenkel", "de.maxhenkel.voicechat")
    strictMaven("https://maven.taumc.org/releases", "TauMC", "org.embeddedt.celeritas")
}

val flkProvidedVersions: Map<String, String> = run {
    configurations.detachedConfiguration(
        dependencies.create("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion"),
    ).resolvedConfiguration.resolvedArtifacts
        .map { it.moduleVersion.id }
        .filter { "${it.group}:${it.name}" in flkProvidedModules }
        .associate { "${it.group}:${it.name}" to it.version }
        .also { resolved ->
            val missing = flkProvidedModules - resolved.keys
            check(missing.isEmpty()) {
                "fabric-language-kotlin $fabricLanguageKotlinVersion no longer ships $missing; " +
                        "drop them from flkProvidedModules so they get bundled again"
            }
        }
}

val flkCoroutinesVersion: String = configurations.detachedConfiguration(
    dependencies.create("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion"),
).resolvedConfiguration.resolvedArtifacts
    .map { it.moduleVersion.id }
    .single { it.group == "org.jetbrains.kotlinx" && it.name == "kotlinx-coroutines-core-jvm" }
    .version

configurations.all {
    resolutionStrategy.eachDependency {
        flkProvidedVersions["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        // OneConfig 1.2.0 for 26.2 declares a snapshot that Sonatype has since purged; the
        // release build targets the same game range. PolyPlus never touches this API directly.
        if (requested.group == "net.kyori" && requested.name == "adventure-platform-fabric" && requested.version == "7.0.0-SNAPSHOT") {
            useVersion("7.0.0")
        }
    }
}

if (isOrnithe) {
    sourceSets.main {
        val excluded = listOf(
            "**/client/network/p2p/P2PVoicechatPlugin.kt",
            "**/mixin/client/MixinGameNarrator.java",
            "**/mixin/client/access/HolderReferenceInvoker.java",
            "**/mixin/client/MixinGuiMessage.java",
            "**/client/network/p2p/EosVoicechat*.kt",
            "**/mixin/compat/animatium/**",
            "**/mixin/compat/essential/**",
            "**/mixin/compat/euphoria/**",
            "**/mixin/compat/mountopacity/**",
            "**/mixin/compat/polytone/**",
            "**/mixin/compat/sodium/**",
            "**/mixin/compat/voicechat/**",
            "**/mixin/compat/waveycapes/**",
            "**/mixin/compat/wwaypoints/**",
        )
        java.exclude(excluded)
        kotlin.exclude(excluded)
    }
}

if (optionalProperty("deps.iris") == null) {
    sourceSets.main {
        val excluded = listOf("**/compat/iris/**")
        java.exclude(excluded)
        kotlin.exclude(excluded)
    }
}

val javaVersion = when {
    isOrnithe || stonecutter.current.parsed >= "26.1" -> 25
    else -> 21
}

configure<KotlinJvmExtension> {
    jvmToolchain(javaVersion)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

val loomExt = extensions.getByName<LoomGradleExtensionAPI>("loom")

val sentryRelocatedPackage = "org.polyfrost.polyplus.libs.sentry"

val sentryShade: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false // io.sentry:sentry has zero runtime dependencies
}

val relocateSentry = tasks.register<ShadowJar>("relocateSentry") {
    group = "build"
    description = "Relocates io.sentry into $sentryRelocatedPackage"

    configurations = listOf(sentryShade)
    relocate("io.sentry", sentryRelocatedPackage)

    exclude(
        "META-INF/native-image/**",
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "module-info.class",
    )

    archiveBaseName = "sentry-relocated"
    archiveVersion = ""
    archiveClassifier = ""
    destinationDirectory = layout.buildDirectory.dir("relocated")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val ktorRelocatedPackage = "org.polyfrost.polyplus.libs.ktor"
val coroutinesRelocatedPackage = "org.polyfrost.polyplus.libs.coroutines"

val ktorShade: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.slf4j")
    for (module in flkProvidedModules) {
        val (group, name) = module.split(":")
        exclude(group = group, module = name)
    }
}

// Ktor gets its own coroutines copy because another mod can ship an older, unrelocated
// kotlinx.coroutines that wins over FLK's. PolyPlus code keeps FLK's coroutines so it can
// still hand flows and scopes to Compose.
val relocateKtor = tasks.register<ShadowJar>("relocateKtor") {
    group = "build"
    description = "Relocates io.ktor into $ktorRelocatedPackage and its kotlinx.coroutines into $coroutinesRelocatedPackage"

    configurations = listOf(ktorShade)
    relocate("io.ktor", ktorRelocatedPackage)
    relocate("kotlinx.coroutines", coroutinesRelocatedPackage)
    relocate("_COROUTINE", "$coroutinesRelocatedPackage._COROUTINE")
    mergeServiceFiles()

    exclude(
        "META-INF/versions/9/module-info.class",
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/com.android.tools/**",
        "META-INF/proguard/**",
        "DebugProbesKt.bin",
        "org/intellij/**",
        "org/jetbrains/annotations/**",
        "module-info.class",
    )

    archiveBaseName = "ktor-relocated"
    archiveVersion = ""
    archiveClassifier = ""
    destinationDirectory = layout.buildDirectory.dir("relocated")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val serializationRelocatedPackage = "org.polyfrost.polyplus.libs.serialization"

val serializationShade: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val mainClassesDirs = sourceSets.main.get().output.classesDirs

val relocateSerialization = tasks.register<ShadowJar>("relocateSerialization") {
    group = "build"
    description = "Relocates kotlinx.serialization in PolyPlus and its Ktor into $serializationRelocatedPackage"

    configurations = listOf(serializationShade)
    from(mainClassesDirs) { include("**/*.class") }
    from(zipTree(relocateKtor.flatMap { it.archiveFile }))
    relocate("kotlinx.serialization", serializationRelocatedPackage)

    exclude(
        "META-INF/MANIFEST.MF",
        "META-INF/versions/9/module-info.class",
        "META-INF/com.android.tools/**",
        "META-INF/proguard/**",
        "module-info.class",
    )

    archiveBaseName = "serialization-relocated"
    archiveVersion = ""
    archiveClassifier = ""
    destinationDirectory = layout.buildDirectory.dir("relocated")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    exclude { it.name.endsWith(".class") && mainClassesDirs.any { dir -> it.file.startsWith(dir) } }
    for (relocated in listOf(relocateSentry, relocateSerialization)) {
        from(zipTree(relocated.flatMap { it.archiveFile })) {
            exclude("META-INF/MANIFEST.MF")
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")

    if (isOrnithe) {
        mappings(ploceus!!.layeredMappings {
            mappings("net.ornithemc:feather-gen2:$mcVersion+build.${property("feather_build")}:v2") {
                containsUnpick()
            }
            mappings(rootProject.file("mappings/feather-overrides.tiny"))
        })
        testCompileOnly("net.ornithemc.osl-gen2:entrypoints:${property("deps.osl_entrypoints")}")
        modImplementation("net.ornithemc.osl-gen2:core:${property("deps.osl_core")}")
        modImplementation("net.ornithemc.osl-gen2:keybinds:${property("deps.osl_keybinds")}")
        include(implementation("org.joml:joml:1.10.8")!!)
        compileOnly("org.embeddedt.celeritas:celeritas-common:2.5.0-pre.1") { isTransitive = false }
    } else {
        dependencies.extensions.getByType<LoomCompatDependencyExtension>().applyMojangMappings()
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    annotationProcessor("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
    "com.github.bawnorton.mixinsquared:mixinsquared-fabric:$mixinSquaredVersion".let {
        annotationProcessor(it)
        implementation(include(it)!!)
    }

    if (!isOrnithe) {
        modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
        modCompileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20") { isTransitive = false }
    }
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    // This is a library, not a traditional mod. It must not use modRuntimeOnly,
    // or it does not get properly loaded into the test environment on 1.21.x.
    runtimeOnly("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion")

    optionalProperty("deps.sodium")?.let {
        modCompileOnly("maven.modrinth:sodium:$it") { isTransitive = false }
    }

    optionalProperty("deps.wwaypoints")?.let {
        modCompileOnly("maven.modrinth:wwaypoints:$it") { isTransitive = false }
    }

    optionalProperty("deps.evergreenhud")?.let {
        modCompileOnly("maven.modrinth:evergreenhud:$it") { isTransitive = false }
        modLocalRuntime("maven.modrinth:evergreenhud:$it") { isTransitive = false }
    }

    optionalProperty("deps.iris")?.let {
        modCompileOnly("maven.modrinth:iris:$it") { isTransitive = false }
    }

    optionalProperty("deps.distanthorizons")?.let {
        modCompileOnly("maven.modrinth:distanthorizons:$it") { isTransitive = false }
    }

    compileOnly("maven.modrinth:debugify:26.2.0.0") { isTransitive = false }

    compileOnly("com.nikoverflow:exploit-preventer-api:1.0.0")

    modImplementation("org.polyfrost.oneconfig:$mcVersion-$loader:$oneconfigVersion") {
        // Loom strips the nested Kotlin jars from a remapped copy, so the plain copy above must stay the only candidate
        exclude(group = "net.fabricmc", module = "fabric-language-kotlin")
    }

    sentryShade("io.sentry:sentry:$sentryVersion")
    implementation(files(relocateSentry.flatMap { it.archiveFile }))
    for (module in ktorModules) ktorShade(module)
    implementation(files(relocateKtor.flatMap { it.archiveFile }))
    for (module in listOf("kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm")) {
        serializationShade("org.jetbrains.kotlinx:$module:${flkProvidedVersions.getValue("org.jetbrains.kotlinx:$module")}")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$flkCoroutinesVersion")
    for ((module, version) in flkProvidedVersions) {
        compileOnly("$module:$version")
        testCompileOnly("$module:$version")
    }

    implementation(include("gg.sona:eos:2.2.0")!!)
    implementation(include("io.github.jaredmdobson:concentus:1.0.2")!!)

    implementation(include("com.moulberry:mixinconstraints:1.1.0")!!)

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("net.fabricmc:fabric-loader-junit:$fabricLoaderVersion")

    errorprone("com.google.errorprone:error_prone_core:${property("deps.errorprone")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks = true
        error("UnnecessarilyFullyQualified")
    }
}

abstract class KotlinCompileLimit : BuildService<BuildServiceParameters.None>

val kotlinCompileLimit = gradle.sharedServices.registerIfAbsent("kotlinCompileLimit", KotlinCompileLimit::class) {
    maxParallelUsages = 3
}

tasks.withType<KotlinCompile>().configureEach {
    usesService(kotlinCompileLimit)
}

tasks.named<JavaCompile>("compileJava") {
    finalizedBy("checkstyleMain")
}

// the enabled Detekt rules need type resolution, which only the per-compilation tasks have
tasks.named<Detekt>("detekt") {
    enabled = false
    dependsOn(tasks.withType<Detekt>().matching { it.name != "detekt" && !it.name.endsWith("SourceSet") })
}

loomExt.uncompressNestedJars = true

val modId = property("mod.id") as String
loomExt.mixin {
    defaultRefmapName.set("mixins.$modId.refmap.json")
}

loomExt.decompilerOptions.named("vineflower") {
    options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
}

val modOutputGroup = sourceSets.main.get().output
    .let { files(it.classesDirs, it.resourcesDir) }
    .joinToString(File.pathSeparator) { it.absolutePath }

loomExt.runs.configureEach {
    ideConfigGenerated(true)
    // Shares the run directory between modern versions; 1.8.9 rewrites options.txt in a format they can't read
    runDir(if (isOrnithe) "../../run/ornithe" else "../../run")
    vmArg("-Dpolyplus.badge.debug=true")
    vmArg("-Dfabric.classPathGroups=$modOutputGroup")
}
loomExt.runs.named("client") {
    client()
    if (isOrnithe && System.getProperty("os.name").startsWith("Mac")) vmArg("-XstartOnFirstThread")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fabric.classPathGroups", modOutputGroup)
    testLogging {
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

val generateTesterFlag = tasks.register("generateTesterFlag") {
    val output = layout.buildDirectory.file("generated/tester/polyplus-tester.properties")
    inputs.property("tester", testerBuild)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("tester=$testerBuild\n")
        }
    }
}

tasks.withType<ProcessResources>().configureEach {
    from(generateTesterFlag)
    val keptChain = when {
        stonecutter.eval(mcVersion, ">= 1.21.8") -> "lobby.json"
        stonecutter.eval(mcVersion, ">= 1.21.5") -> "lobby_uniforms.json"
        else -> "lobby_program.json"
    }
    val oldEffects = stonecutter.eval(mcVersion, "< 1.21.4")
    val shaders = "assets/polyplus/shaders"
    includeEmptyDirs = false
    if (oldEffects) {
        exclude("assets/polyplus/post_effect/**", "$shaders/core/lobby_lightmap.*")
    } else {
        (listOf("lobby.json", "lobby_uniforms.json", "lobby_program.json") - keptChain).forEach { exclude("assets/polyplus/post_effect/$it") }
        exclude("$shaders/effect/**")
    }
    if (!stonecutter.eval(mcVersion, "= 1.21.4")) exclude("$shaders/core/*.json", "$shaders/post/*.json")
    if (stonecutter.eval(mcVersion, ">= 1.21.10")) exclude("$shaders/core/lobby_entity.*", "$shaders/core/lobby_lightmap.*")
    if (isOrnithe) {
        exclude("$shaders/core/**", "$shaders/effect/core/**", "$shaders/effect/lobby_chain.json", "assets/polyplus/lobby/scenes/**")
    } else {
        exclude("$shaders/legacy/**", "assets/polyplus/lobby/scenes_legacy/**", "assets/minecraft/font/default.json")
    }
    filesMatching(listOf("$shaders/**/*.fsh", "$shaders/**/*.vsh")) {
        var inactive = false
        filter { line ->
            val trimmed = line.trimStart()
            val block = inactive || trimmed.startsWith("/*")
            if (block) inactive = "*/" !in if (inactive) line else trimmed.drop(2)
            line.takeUnless { block || trimmed.startsWith("//") }
        }
    }
    if (oldEffects) {
        filesMatching(listOf("$shaders/effect/lobby_chain.json", "$shaders/legacy/lobby_chain.json")) { path = "$shaders/post/lobby_chain.json" }
        filesMatching("$shaders/effect/core/*") { path = "$shaders/core/$name" }
        filesMatching(listOf("$shaders/effect/program/*", "$shaders/post/*")) { path = "assets/minecraft/shaders/program/polyplus_$name" }
    }
    val modName = project.property("mod.name") as String
    val modVersion = project.property("mod.version") as String
    inputs.property("modId", modId)
    inputs.property("modName", modName)
    inputs.property("modVersion", modVersion)
    inputs.property("minorMcVersion", minecraftPredicate)
    inputs.property("oneconfigVersion", oneconfigVersion)
    filesMatching("fabric.mod.json") {
        if (isOrnithe) filter { line ->
            line.replace("\"fabric-api\": \"*\"", "\"osl-keybinds\": \"*\"")
                .replace("\"java\": \">=21\"", "\"java\": \">=25\"")
        }
        expand(
            mapOf(
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_version" to modVersion,
                "mod_description" to "SleepClient - Premium Custom Minecraft Client",
                "minor_mc_version" to minecraftPredicate,
                "oneconfig_version" to oneconfigVersion,
                "mod_loader" to if (isOrnithe) "ornithe" else "fabric",
            ),
        )
    }
    filesMatching("mixins.*.json") {
        expand("id" to modId)
    }
}

tasks.named<Jar>("sourcesJar") {
    exclude("**/*.png")
    exclude("assets/**")
    exclude("natives/**")
}

tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn("stonecutterGenerate")
}

val modVersion = property("mod.version") as String
val expectedModName = property("mod.name") as String
val runtimeJar = loomx.modJar.flatMap { it.archiveFile }
val sourcesJar = loomx.modSourcesJar.flatMap { it.archiveFile }
val runtimeJarName = "$modId-${project.version}.jar"
val finalRuntimeJar = rootProject.layout.buildDirectory.file("final/$runtimeJarName")
val finalSourcesJar = rootProject.layout.buildDirectory.file("final/sources/$modId-${project.version}-sources.jar")
val legacyRuntimeJar = rootProject.layout.buildDirectory.file("libs/$modVersion/$runtimeJarName")
val legacySourcesJar = rootProject.layout.buildDirectory.file("libs/$modVersion/$modId-${project.version}-sources.jar")
val modpackJarName = "$expectedModName-$mcVersion-$loader.jar"
val modpackJar = rootProject.layout.buildDirectory.file("release/mods/$modpackJarName")
val modpackChecksums = rootProject.layout.buildDirectory.file("release/SHA256SUMS.txt")
val configuredSleepClientProfileMods = optionalProperty("sleepclient.profile.modsDir")?.let(::File)
val profileMinecraftVersion = (property("mod.mc_releases") as String).split(',').last().trim()
val defaultSleepClientProfileMods = System.getenv("APPDATA")?.let {
    File(it, "SleepClient/SleepClient/clusters/$profileMinecraftVersion ${if (isOrnithe) "Ornithe" else "Fabric"}/mods")
}
val sleepClientProfileMods = configuredSleepClientProfileMods ?: defaultSleepClientProfileMods
    ?.takeIf { it.isDirectory }
val sleepClientProfileJar = sleepClientProfileMods?.resolve(modpackJarName)
data class SleepClientRuntimeDependency(
    val modId: String,
    val version: String,
    val source: File,
    val targetName: String,
)

fun resolveSleepClientRuntimeDependencies(): List<SleepClientRuntimeDependency> {
    val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
    val polyCompose = artifacts.filter {
        val module = it.moduleVersion.id
        module.group in setOf("remapped.org.polyfrost", "org.polyfrost") &&
            (module.name == "polycompose-$mcVersion-$loader" ||
                module.name.startsWith("polycompose-$mcVersion-$loader-"))
    }
    if (polyCompose.size != 1) {
        throw GradleException(
            "Expected one remapped PolyCompose dependency for $mcVersion-$loader; found ${polyCompose.size}",
        )
    }

    val oneConfigDistribution = rootProject.projectDir.parentFile.resolve(
        "OneConfig/minecraft/versions/$mcVersion-$loader/build/dist/" +
            "OneConfig-$mcVersion-$loader-$oneconfigVersion.jar",
    )
    if (!oneConfigDistribution.isFile) {
        throw GradleException("SleepClient OneConfig distribution not found: ${oneConfigDistribution.absolutePath}")
    }

    val polyComposeArtifact = polyCompose.single()
    return listOf(
        SleepClientRuntimeDependency(
            "oneconfig",
            oneconfigVersion,
            oneConfigDistribution,
            "SleepClient-UI-$mcVersion-$loader-$oneconfigVersion.jar",
        ),
        SleepClientRuntimeDependency(
            "polycompose",
            polyComposeArtifact.moduleVersion.id.version,
            polyComposeArtifact.file,
            "polycompose-$mcVersion-$loader-${polyComposeArtifact.moduleVersion.id.version}.jar",
        ),
    )
}

val buildTask = tasks.named("build")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun copyAndVerify(source: File, target: File, label: String) {
    Files.createDirectories(target.parentFile.toPath())
    val temporary = Files.createTempFile(target.parentFile.toPath(), ".sleepclient-", ".tmp")
    try {
        Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
        if (sha256(source) != sha256(temporary.toFile())) {
            throw GradleException("$label JAR copy failed verification: ${target.absolutePath}")
        }
        Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

fun verifySleepClientDependency(dependency: SleepClientRuntimeDependency, file: File) {
    if (!file.isFile) throw GradleException("SleepClient ${dependency.modId} dependency not found: ${file.absolutePath}")

    ZipFile(file).use { zip ->
        val metadataEntry = zip.getEntry("fabric.mod.json") ?: zip.getEntry("ornithe.mod.json")
            ?: throw GradleException("SleepClient ${dependency.modId} dependency is missing mod metadata")
        val metadata = zip.getInputStream(metadataEntry).bufferedReader(Charsets.UTF_8).use {
            JsonSlurper().parseText(it.readText()) as? Map<*, *>
                ?: throw GradleException("Invalid ${metadataEntry.name} in ${file.name}")
        }

        if (metadata["id"] != dependency.modId) {
            throw GradleException("Unexpected mod ID in ${file.name}: ${metadata["id"]}")
        }
        if (metadata["version"] != dependency.version) {
            throw GradleException("Unexpected mod version in ${file.name}: ${metadata["version"]}")
        }
        if (dependency.modId == "oneconfig") {
            if (metadata["name"] != "SleepClient") {
                throw GradleException("OneConfig runtime dependency is not branded SleepClient")
            }
            val providedIds = (metadata["provides"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            if ("oneconfigv1" !in providedIds) {
                throw GradleException("SleepClient UI dependency no longer provides the required OneConfig API ID")
            }
        }
    }
}

val verifySleepClientArchive: (File) -> Unit = { file ->
    if (!file.isFile) throw GradleException("SleepClient runtime JAR not found: ${file.absolutePath}")
    if (file.name != runtimeJarName) {
        throw GradleException("Unexpected runtime JAR name '${file.name}', expected '$runtimeJarName'")
    }

    ZipFile(file).use { zip ->
        val entries = Collections.list(zip.entries())
        val entryNames = entries.map { it.name }.toSet()
        val metadataEntry = zip.getEntry("fabric.mod.json")
            ?: throw GradleException("SleepClient JAR is missing fabric.mod.json")
        val metadata = zip.getInputStream(metadataEntry).bufferedReader(Charsets.UTF_8).use {
            JsonSlurper().parseText(it.readText()) as? Map<*, *>
                ?: throw GradleException("Invalid fabric.mod.json in ${file.name}")
        }

        if (metadata["id"] != modId) throw GradleException("Unexpected mod ID in ${file.name}: ${metadata["id"]}")
        if (metadata["name"] != expectedModName) {
            throw GradleException("Unexpected mod name in ${file.name}: ${metadata["name"]}")
        }
        if (metadata["version"] != modVersion) {
            throw GradleException("Unexpected mod version in ${file.name}: ${metadata["version"]}")
        }
        val providedIds = (metadata["provides"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        if ("oneclient" in providedIds) {
            throw GradleException("SleepClient metadata must not advertise the legacy OneClient mod ID")
        }

        val dependencies = metadata["depends"] as? Map<*, *>
            ?: throw GradleException("SleepClient JAR is missing dependency metadata")
        if (dependencies["oneconfigv1"] != ">=$oneconfigVersion") {
            throw GradleException("SleepClient JAR has an incorrect OneConfig dependency")
        }
        if (dependencies["polycompose"] != ">=1.0.0") {
            throw GradleException("SleepClient JAR has an incorrect PolyCompose dependency")
        }

        val requiredEntries = mutableSetOf(
            "assets/polyplus/brand/sleepclient.svg",
            "org/polyfrost/polyplus/PolyPlusEntrypoint.class",
            "org/polyfrost/polyplus/client/gui/PolyPlusOneConfigIntegration.class",
            "org/polyfrost/polyplus/client/party/Parties.class",
            "org/polyfrost/polyplus/client/party/PartyVoiceHud.class",
            "org/polyfrost/polyplus/client/gui/ClientWatermark.class",
            "org/polyfrost/polyplus/mixin/client/MixinScreenWatermark.class",
        )
        if (isOrnithe) {
            requiredEntries.add("org/polyfrost/polyplus/mixin/client/MixinInventoryMenuScreen.class")
        } else {
            requiredEntries.add("org/polyfrost/polyplus/mixin/client/MixinWatermarkMouse.class")
        }
        if (mcVersion == "1.21.11") {
            requiredEntries.add("org/polyfrost/polyplus/mixin/client/MixinMenuMusic.class")
        }
        val missingEntries = requiredEntries - entryNames
        if (missingEntries.isNotEmpty()) {
            throw GradleException("SleepClient JAR is missing required entries: ${missingEntries.joinToString()}")
        }
        val tutorialEntries = entryNames.filter {
            it.startsWith("org/polyfrost/polyplus/client/gui/lobbyshell/LobbyTour")
        }
        if (tutorialEntries.isNotEmpty()) {
            throw GradleException("SleepClient JAR still contains the removed lobby tutorial: ${tutorialEntries.joinToString()}")
        }

        val mixinFiles = metadata["mixins"] as? List<*>
            ?: throw GradleException("SleepClient JAR is missing its mixin configuration")
        val mixinFileName = mixinFiles.filterIsInstance<String>().firstOrNull { it == "mixins.sleepclient.json" }
            ?: throw GradleException("SleepClient JAR is missing mixins.sleepclient.json")
        val mixinEntry = zip.getEntry(mixinFileName)
            ?: throw GradleException("SleepClient JAR is missing $mixinFileName")
        val mixinJson = zip.getInputStream(mixinEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//[^\r\n]*(?:\r?\n|$)"""), "")
            .replace(Regex(""",\s*([}\]])"""), "\$1")
        val mixinConfig = JsonSlurper().parseText(mixinJson) as? Map<*, *>
            ?: throw GradleException("Invalid $mixinFileName in ${file.name}")
        val clientMixins = (mixinConfig["client"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val commonMixins = (mixinConfig["mixins"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val activeMixins = (clientMixins + commonMixins).toSet()
        val forbiddenScreenMixins = setOf(
            "client.MixinGui",
            "client.MixinTitleScreen",
            "client.MixinPauseScreen",
            "client.MixinGenericMessageScreen",
            "client.MixinLevelLoadingScreen",
            "client.MixinMinecraftWorldLoad",
            "client.MixinGameRendererShellScene",
            "client.MixinScreen",
            "client.MixinPanorama",
            "client.MixinCubeMap",
            "client.MixinProgressRenderer",
            "client.lobby.MixinScreen",
            "client.lobby.MixinLoadingOverlay",
            "client.lobby.MixinInitialScreens",
            "client.lobby.MixinMinecraftStartup",
            "client.pause.MixinPauseCamera",
            "client.pause.MixinPauseHand",
            "client.pause.MixinPauseCulling",
            "client.pause.MixinPauseRenderer",
            "compat.confirmdisconnect.MixinConfirmDisconnectPauseScreen",
        )
        val remainingScreenMixins = forbiddenScreenMixins intersect activeMixins
        if (remainingScreenMixins.isNotEmpty()) {
            throw GradleException("SleepClient JAR still registers custom screen mixins: ${remainingScreenMixins.joinToString()}")
        }
        val requiredHudMixins = setOf(
            "client.MixinScreenWatermark",
            if (isOrnithe) "client.MixinInventoryMenuScreen" else "client.MixinWatermarkMouse",
        )
        val missingHudMixins = requiredHudMixins - clientMixins.toSet()
        if (missingHudMixins.isNotEmpty()) {
            throw GradleException("SleepClient JAR is missing HUD mixins: ${missingHudMixins.joinToString()}")
        }
        if (mcVersion == "1.21.11" && "client.MixinMenuMusic" !in clientMixins) {
            throw GradleException("SleepClient JAR does not register menu-only music suppression")
        }

        val legacyBrandAssets = entries.asSequence()
            .map { it.name }
            .filter { it.startsWith("assets/") && it.contains("oneclient", ignoreCase = true) }
            .toList()
        if (legacyBrandAssets.isNotEmpty()) {
            throw GradleException("SleepClient JAR contains legacy OneClient assets: ${legacyBrandAssets.joinToString()}")
        }

        val textExtensions = setOf("css", "html", "json", "lang", "properties", "svg", "toml", "txt", "xml", "yaml", "yml")
        for (entry in entries) {
            if (entry.isDirectory) continue
            zip.getInputStream(entry).use { input ->
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                if (entry.name.startsWith("assets/") && extension in textExtensions) {
                    val content = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (content.contains("oneclient", ignoreCase = true)) {
                        throw GradleException("SleepClient resource ${entry.name} contains legacy OneClient text")
                    }
                    if (entry.name == "assets/polyplus/brand/sleepclient.svg" && !content.contains("SleepClient")) {
                        throw GradleException("SleepClient wordmark is missing its product name")
                    }
                } else {
                    input.copyTo(OutputStream.nullOutputStream())
                }
            }
        }
    }
}

tasks.register("verifySleepClientJar") {
    group = "verification"
    description = "Validates SleepClient metadata, Vanilla screen policy, preserved HUD, branding, and dependencies."
    dependsOn(loomx.modJar)
    inputs.file(runtimeJar)
    doLast {
        verifySleepClientArchive(runtimeJar.get().asFile)
    }
}

val collectFinalArtifacts = tasks.register("collectFinalArtifacts") {
    group = "build"
    description = "Verifies and collects the versioned runtime JAR under `build/final/`."

    dependsOn("verifySleepClientJar", loomx.modSourcesJar)
    mustRunAfter(buildTask)
    onlyIf("the complete build must succeed before collecting artifacts") {
        buildTask.get().state.failure == null
    }
    inputs.files(runtimeJar, sourcesJar)
    outputs.files(finalRuntimeJar, finalSourcesJar, modpackJar)
    outputs.upToDateWhen { false }
    sleepClientProfileJar?.let { outputs.file(it) }
    doLast {
        val runtimeSource = runtimeJar.get().asFile
        val sourcesSource = sourcesJar.get().asFile
        val runtimeTarget = finalRuntimeJar.get().asFile
        val sourcesTarget = finalSourcesJar.get().asFile
        val modpackTarget = modpackJar.get().asFile
        val checksumsTarget = modpackChecksums.get().asFile
        if (!sourcesSource.isFile) throw GradleException("SleepClient sources JAR not found: ${sourcesSource.absolutePath}")

        Files.createDirectories(runtimeTarget.parentFile.toPath())
        Files.createDirectories(sourcesTarget.parentFile.toPath())
        Files.createDirectories(modpackTarget.parentFile.toPath())
        Files.copy(runtimeSource.toPath(), runtimeTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(sourcesSource.toPath(), sourcesTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            verifySleepClientArchive(runtimeTarget)
        } catch (error: Exception) {
            Files.deleteIfExists(runtimeTarget.toPath())
            throw error
        }
        Files.deleteIfExists(legacyRuntimeJar.get().asFile.toPath())
        Files.deleteIfExists(legacySourcesJar.get().asFile.toPath())

        synchronized(rootProject) {
            val sleepClientRuntimeDependencies = resolveSleepClientRuntimeDependencies()
            val runtimeHash = sha256(runtimeTarget)
            copyAndVerify(runtimeTarget, modpackTarget, "Modpack")
            val managedDependencyPrefixes = listOf(
                "OneConfig-$mcVersion-$loader-",
                "SleepClient-UI-$mcVersion-$loader-",
                "polycompose-$mcVersion-$loader-",
            )
            val dependencyTargets = sleepClientRuntimeDependencies.map { dependency ->
                val target = rootProject.layout.buildDirectory
                    .file("release/mods/${dependency.targetName}")
                    .get()
                    .asFile
                copyAndVerify(dependency.source, target, dependency.modId)
                verifySleepClientDependency(dependency, target)
                target
            }
            val releaseModsDir = modpackTarget.parentFile
            releaseModsDir.listFiles()
                ?.filter { file -> file.isFile && managedDependencyPrefixes.any(file.name::startsWith) }
                ?.filterNot { stale -> dependencyTargets.any { it.canonicalFile == stale.canonicalFile } }
                ?.forEach { stale -> Files.deleteIfExists(stale.toPath()) }

            val existingLines = if (checksumsTarget.isFile) {
                Files.readAllLines(checksumsTarget.toPath())
            } else {
                emptyList()
            }
            val managedNames = dependencyTargets.map { it.name }.toSet() + modpackJarName
            val updatedLines = existingLines
                .filterNot { line ->
                    val name = line.substringAfter(" *", "").substringBefore(' ')
                    name in managedNames || managedDependencyPrefixes.any(name::startsWith)
                }
                .toMutableList()
            updatedLines.add("$runtimeHash *$modpackJarName ${modpackTarget.length()} bytes")
            dependencyTargets.forEach { dependency ->
                updatedLines.add("${sha256(dependency)} *${dependency.name} ${dependency.length()} bytes")
            }
            updatedLines.sortBy { it.substringAfter(" *", "").substringBefore(' ') }

            Files.createDirectories(checksumsTarget.parentFile.toPath())
            val checksumsTemp = Files.createTempFile(checksumsTarget.parentFile.toPath(), ".sha256-", ".tmp")
            try {
                Files.write(checksumsTemp, updatedLines, Charsets.UTF_8)
                Files.move(checksumsTemp, checksumsTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(checksumsTemp)
            }

            sleepClientProfileJar?.let { profileJar ->
                copyAndVerify(runtimeTarget, profileJar, "SleepClient profile")
                logger.lifecycle("Updated SleepClient profile JAR: ${profileJar.absolutePath}")
            }
            sleepClientProfileMods?.let { profileMods ->
                val profilePrefixes = managedDependencyPrefixes
                profileMods.listFiles()
                    ?.filter { file -> file.isFile && profilePrefixes.any(file.name::startsWith) }
                    ?.filterNot { stale -> sleepClientRuntimeDependencies.any { profileMods.resolve(it.targetName).canonicalFile == stale.canonicalFile } }
                    ?.forEach { stale -> Files.deleteIfExists(stale.toPath()) }
                sleepClientRuntimeDependencies.forEach { dependency ->
                    val target = profileMods.resolve(dependency.targetName)
                    copyAndVerify(dependency.source, target, "SleepClient ${dependency.modId} profile")
                    verifySleepClientDependency(dependency, target)
                    logger.lifecycle("Updated SleepClient ${dependency.modId} dependency: ${target.absolutePath}")
                }
            }
        }

        logger.lifecycle("Verified current SleepClient runtime JAR: ${runtimeTarget.absolutePath}")
        logger.lifecycle("Sources JAR collected separately: ${sourcesTarget.absolutePath}")
        logger.lifecycle("Updated modpack runtime JAR: ${modpackTarget.absolutePath}")
    }
}

if (project != rootProject) {
    buildTask.configure {
        finalizedBy(collectFinalArtifacts)
    }
}

tasks.register("buildAndCollect") {
    group = "build"
    description = "Runs the complete build, then verifies and collects versioned runtime and sources JARs."
    dependsOn(buildTask, collectFinalArtifacts)
}

// these intentionally support multiple different properties, do not remove
val modrinthId = optionalProperty("oneconfig.publish.modrinth", "publish.modrinth")
val modrinthToken = optionalProperty("oneconfig.publish.modrinth.token", "publish.modrinth.token", "modrinth.token")
val curseforgeId = optionalProperty("oneconfig.publish.curseforge", "publish.curseforge")
val curseforgeToken = optionalProperty("oneconfig.publish.curseforge.token", "publish.curseforge.token", "curseforge.token")

val minecraftVersion = (property("mod.mc_releases") as String).split(",").map { it.trim() }
val changelogFile = rootProject.file("CHANGELOG.md")

fun currentChangelogSection(): String? {
    if (!changelogFile.exists()) return null
    val headingPattern = Regex("^##\\s+\\[?v?" + Regex.escape(modVersion) + "]?(\\s.*)?$")
    val lines = changelogFile.readLines()
    val start = lines.indexOfFirst { headingPattern.matches(it.trimEnd()) }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
    val section = lines.subList(start + 1, end)
    if (section.none { it.isNotBlank() && !it.trimStart().startsWith("#") }) return null
    return section.joinToString("\n").trim()
}

val validateChangelog = tasks.register("validateChangelog") {
    description = "Validates that the changelog is written for the current version."
    doLast {
        if (currentChangelogSection() == null) {
            throw GradleException("Changelog for version $modVersion not found.")
        }
    }
}

tasks.matching { it.name == "publishMods" || it.name == "publishModrinth" || it.name == "publishCurseforge" }.configureEach {
    dependsOn(validateChangelog)
}

tasks.matching { it.name.startsWith("publish") }.configureEach {
    doFirst {
        if (testerBuild) throw GradleException("Tester builds must never be published.")
    }
}

publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }

    displayName = modVersion
    version = "v$modVersion"
    changelog = providers.provider { currentChangelogSection() ?: throw GradleException("Changelog for version $modVersion not found.") }
    type = STABLE

    modLoaders.add(loader)

    dryRun = modrinthId == null || modrinthToken == null || (curseforgeId != null && curseforgeToken == null)

    if (modrinthId != null) {
        modrinth {
            projectId = modrinthId
            accessToken = modrinthToken.orEmpty()

            minecraftVersions.addAll(minecraftVersion)

            requires("oneconfig")
            requires("fabric-language-kotlin")
        }
    }

    if (curseforgeId != null && !isOrnithe) {
        curseforge {
            projectId = curseforgeId
            accessToken = curseforgeToken.orEmpty()

            minecraftVersions.addAll(minecraftVersion)
            client = true

            requires("oneconfig")
            requires("fabric-language-kotlin")
        }
    }
}

val snapshotRelease = modVersion.endsWith("-SNAPSHOT")

publishing {
    repositories {
        val repoName = if (snapshotRelease) "snapshots" else "releases"
        maven("https://repo.polyfrost.org/$repoName") {
            name = repoName
            credentials {
                username = providers.gradleProperty("polyfrostRepoUsername")
                    .orElse(providers.environmentVariable("POLYFROST_REPO_USERNAME")).orNull
                password = providers.gradleProperty("polyfrostRepoToken")
                    .orElse(providers.environmentVariable("POLYFROST_REPO_TOKEN")).orNull
            }
            authentication { create<BasicAuthentication>("basic") }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = modId
            if (snapshotRelease) version = "${modVersion.removeSuffix("-SNAPSHOT")}+$mcVersion-SNAPSHOT"
            artifact(loomx.modJar.flatMap { it.archiveFile })
            artifact(loomx.modSourcesJar.flatMap { it.archiveFile }) { classifier = "sources" }
        }
    }
}
