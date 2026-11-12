import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.CRC32

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.retromobilebench"
    compileSdk = 37

    // NDK r23c is the last NDK that supports API 16-18 and non-NEON ARMv7 (docs/02-platform-constraints.md).
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "com.retromobilebench"
        minSdk = 16
        // 23: lowest target Android 14+ will install; no behaviour change on 4.x (README "发布方式").
        targetSdk = 23
        versionCode = 1
        versionName = "0.0.1"
        // Framework Instrumentation works on API 16 without AndroidX/JUnit dependencies.
        testInstrumentationRunner = "com.retromobilebench.BackendInstrumentation"

        ndk {
            abiFilters += listOf("armeabi-v7a", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-16",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // D8 desugars Java 9-17 language features and backports the java.util.Objects etc.
        // calls javac emits, so this is safe for minSdk 16.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Distributed directly for Android 4.x; target 23 is intentional (README).
        // Keep compatibility checks fatal, rather than raising targetSdk to satisfy Play.
        disable += "ExpiredTargetSdkVersion"
        error += listOf("NewApi", "InlinedApi")
    }
}

// Package complete upstream notices without copying or editing the upstream files.
abstract class BenchmarkNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile.resolve("benchmark-notices.txt")
        destination.parentFile.mkdirs()
        destination.writeText(noticeFiles.files.sortedBy { it.parentFile.name }.joinToString("\n\n────────────────\n\n") {
            it.parentFile.name + " / " + it.name + "\n\n" + it.readText()
        })
    }
}

val benchmarkNotices = tasks.register<BenchmarkNotices>("generateBenchmarkNotices") {
    noticeFiles.from(
        rootProject.file("third_party/coremark/LICENSE.md"),
        rootProject.file("third_party/stream/LICENSE.txt"),
        rootProject.file("third_party/tinymembench/util.h"),
        rootProject.file("third_party/gfxbench/LICENSE"),
        rootProject.file("third_party/gfxbench/3rdparty/libpng/LICENSE"),
        rootProject.file("third_party/gfxbench/3rdparty/zlib/zlib.h"),
        rootProject.file("third_party/gfxbench/frameworks/kcl_framework/kcl_libraries/tinyxml/src/tinyxml.h"),
        rootProject.file("third_party/gfxbench/frameworks/kcl_framework/kcl_libraries/tinyxml2/src/tinyxml2.h"),
        rootProject.file("third_party/gfxbench/frameworks/kcl_framework/kcl_libraries/libetc1/src/etc1.cpp"),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/benchmarkNotices"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(benchmarkNotices, BenchmarkNotices::outputDirectory)
}

// Reproducible ETC1 pack; the normal APK contains low-level data only.
// -PgpuData=full also includes T-Rex/Egypt after their upstream files are checked out.
abstract class GpuPackTask : DefaultTask() {
    @get:Internal abstract val gfxDirectory: DirectoryProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Input abstract val fullPack: Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val source = gfxDirectory.get().asFile
        val selected = linkedMapOf<String, java.io.File>()
        val tests = listOf("alu", "fill", "driver", "blending") + if (fullPack.get()) listOf("trex", "egypt") else emptyList()
        tests.forEach { test -> selected["config/gl_${test}_off.json"] = source.resolve("gfxbench40/resources/gfxbench_gl/config/gl_${test}_off.json") }
        val directories = listOf("common", "fill") + if (fullPack.get()) listOf("trex", "egypt") else emptyList()
        directories.forEach { name ->
            val root = source.resolve("gfxbench-data40/data/gfx/$name")
            check(root.isDirectory) { "Missing GPU data: $root. Initialize the sparse dependency data first." }
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val parts = relative.split('/')
                if (parts.none { it.startsWith("images_") && it != "images_ETC1" }
                        && !(name == "common" && file.name.startsWith("ref_"))) selected["data/gfx/$name/$relative"] = file
            }
        }
        listOf("lowlevel", "shaders.20").forEach { name ->
            val root = source.resolve("gfxbench40/resources/data/gfx/shaders_40/$name")
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                selected["data/gfx/shaders_40/$name/${file.relativeTo(root).invariantSeparatorsPath}"] = file
            }
        }
        fun bytes(path: String, file: java.io.File): ByteArray {
            if (!path.endsWith("/lowlevel/ui.fs")) return file.readBytes()
            return file.readText().replace("precision highp float;",
                "// RMB: support ES2 GPUs without fragment highp.\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif").toByteArray(Charsets.UTF_8)
        }
        val entries = selected.toSortedMap()
        val manifestFiles = entries.map { (path, file) ->
            val data = bytes(path, file)
            mapOf("path" to path, "size" to data.size,
                "sha256" to MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        }
        val manifest = groovy.json.JsonOutput.toJson(mapOf("format" to 2,
            "source" to "89aa956f43836798d1eaaab8ec32d3dc8b02eead", "texture_type" to "ETC1",
            "tests" to tests.map { "gl_${it}_off" }, "files" to manifestFiles,
            "license" to "CC BY 4.0 https://creativecommons.org/licenses/by/4.0/ Copyright 2005-2025 Kishonti Ltd; ETC1 subset, reference images removed, ui.fs precision fallback"))
        val destination = outputDirectory.get().asFile.resolve("gpu-data.zip")
        destination.parentFile.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            fun add(path: String, data: ByteArray) {
                val entry = ZipEntry(path)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                val crc = CRC32(); crc.update(data); entry.crc = crc.value; entry.time = 0
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
            add("manifest.json", manifest.toByteArray(Charsets.UTF_8))
            entries.forEach { (path, file) -> add(path, bytes(path, file)) }
        }
    }
}
val gpuPack = tasks.register<GpuPackTask>("generateGpuPack") {
    gfxDirectory.set(rootProject.layout.projectDirectory.dir("third_party/gfxbench"))
    sourceFiles.from(rootProject.fileTree("third_party/gfxbench") {
        include("gfxbench40/resources/gfxbench_gl/config/*_off.json")
        include("gfxbench40/resources/data/gfx/shaders_40/lowlevel/**")
        include("gfxbench40/resources/data/gfx/shaders_40/shaders.20/**")
        include("gfxbench-data40/data/gfx/common/**", "gfxbench-data40/data/gfx/fill/**")
        if (providers.gradleProperty("gpuData").orNull == "full")
            include("gfxbench-data40/data/gfx/trex/**", "gfxbench-data40/data/gfx/egypt/**")
    })
    fullPack.set(providers.gradleProperty("gpuData").map { it == "full" }.orElse(false))
    outputDirectory.set(layout.buildDirectory.dir("generated/gpuPack"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(gpuPack, GpuPackTask::outputDirectory)
}

dependencies {
    // Intentionally empty: no AndroidX (minSdk 21+), no Kotlin stdlib (docs/02-platform-constraints.md).
}
