import java.security.MessageDigest
import java.net.URI
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.CRC32

plugins {
    alias(libs.plugins.android.application)
}

// Changing native sources or toolchain creates a separate score comparison group.
val nativeInputs = files(rootProject.file("app/build.gradle.kts"),
    rootProject.fileTree("app/src/main/java") { include("**/*.java") },
    rootProject.fileTree("native") { include("**/*.c", "**/*.h", "**/*.cmake", "**/CMakeLists.txt") },
    rootProject.fileTree("feasibility/gfxbench-es2") { include("**/*.cpp", "**/*.h", "**/CMakeLists.txt") },
    rootProject.fileTree("third_party") {
        include("**/*.c", "**/*.cpp", "**/*.h", "**/*.hpp")
        exclude("**/.git/**", "**/build/**")
    })
val nativeBuildId = providers.provider {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("r10e-gcc4.9-gnustl_static-O2-api8-armv7-api9-x86-v1".toByteArray())
    nativeInputs.files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach {
        digest.update(it.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        it.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) {
            val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
    }
    "r10e-gcc49-" + digest.digest().take(12).joinToString("") { "%02x".format(it) }
}

val legacyNdkDirectory = rootProject.layout.projectDirectory.dir(
    providers.gradleProperty("legacyNdkDir").orElse(".tools/android-ndk-r10e").get())

abstract class PrepareLegacyNdk : DefaultTask() {
    @get:OutputDirectory abstract val ndkDirectory: DirectoryProperty
    @get:Input abstract val archiveUrl: Property<String>
    @get:Input abstract val archiveSha256: Property<String>
    @TaskAction fun prepare() {
        val destination = ndkDirectory.get().asFile
        val marker = destination.resolve(".rmb-r10e-sha256")
        if (marker.isFile && marker.readText().trim() == archiveSha256.get()) return
        val archive = temporaryDir.resolve("ndk.zip")
        val archiveSize = 1066110352L
        if (archive.length() != archiveSize) {
            logger.lifecycle("Downloading the official NDK r10e archive (about 1 GB, resumable parts)")
            val pool = Executors.newFixedThreadPool(8)
            val partSize = 16L * 1024 * 1024
            val parts = (0 until ((archiveSize + partSize - 1) / partSize).toInt()).map { index ->
                val start = index * partSize
                val end = minOf(archiveSize, start + partSize) - 1
                val part = temporaryDir.resolve("ndk.part$index")
                pool.submit<File> {
                    if (part.length() != end - start + 1) {
                        val connection = URI(archiveUrl.get()).toURL().openConnection() as HttpURLConnection
                        connection.connectTimeout = 30000; connection.readTimeout = 60000
                        connection.setRequestProperty("Range", "bytes=$start-$end")
                        try {
                            check(connection.responseCode == 206) { "NDK server did not accept range request" }
                            connection.inputStream.use { input -> part.outputStream().buffered().use { input.copyTo(it, 65536) } }
                            check(part.length() == end - start + 1) { "Incomplete NDK download part $index" }
                            logger.lifecycle("NDK part ${index + 1}/64 downloaded")
                        } finally { connection.disconnect() }
                    }
                    part
                }
            }
            try {
                val completed = parts.map { it.get() }
                archive.outputStream().buffered().use { output -> completed.forEach { part ->
                    part.inputStream().use { it.copyTo(output, 65536) }
                } }
            } finally { pool.shutdownNow() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
            val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n)
        } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == archiveSha256.get()) { "NDK checksum mismatch: $actual" }
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val relative = entry.name.removePrefix("android-ndk-r10e/")
                val output = destination.resolve(relative).canonicalFile
                check(output.toPath().startsWith(destination.canonicalFile.toPath())) { "Invalid archive path" }
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        check(destination.resolve("RELEASE.TXT").readText().trim().startsWith("r10e")) { "Invalid NDK archive" }
        marker.writeText(actual + "\n")
    }
}
val prepareLegacyNdk = tasks.register<PrepareLegacyNdk>("prepareLegacyNdk") {
    ndkDirectory.set(legacyNdkDirectory)
    archiveUrl.set("https://dl.google.com/android/repository/android-ndk-r10e-windows-x86_64.zip")
    // SHA-256 of the archive retrieved from the official HTTPS URL above.
    archiveSha256.set("4be4192971e1ced4cfb3da8e1242f80741d46c9cb4b44650af17b8053a3f8871")
}

abstract class LegacyNativeBuild : DefaultTask() {
    @get:Inject abstract val execOperations: org.gradle.process.ExecOperations
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input abstract val buildId: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun build() {
        val ndk = ndkDirectory.get().asFile
        check(ndk.resolve("RELEASE.TXT").isFile && ndk.resolve("RELEASE.TXT").readText().trim().startsWith("r10e")) {
            "Run :app:prepareLegacyNdk first, or pass -PlegacyNdkDir=<r10e directory>"
        }
        val cmakeBin = sdkDirectory.get().asFile.resolve("cmake/3.18.1/bin")
        val root = rootDirectory.get().asFile
        listOf("armeabi-v7a", "x86").forEach { abi ->
            val build = temporaryDir.resolve("gcc49/$abi")
            execOperations.exec {
                commandLine(cmakeBin.resolve("cmake.exe"), "-S", root.resolve("native"), "-B", build,
                    "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${cmakeBin.resolve("ninja.exe").invariantSeparatorsPath}",
                    "-DCMAKE_SYSTEM_NAME=Android", "-DCMAKE_SYSTEM_VERSION=${if (abi == "x86") 9 else 8}",
                    "-DCMAKE_ANDROID_ARCH_ABI=$abi", "-DCMAKE_ANDROID_NDK=${ndk.invariantSeparatorsPath}",
                    "-DCMAKE_ANDROID_NDK_TOOLCHAIN_VERSION=4.9", "-DCMAKE_ANDROID_STL_TYPE=gnustl_static",
                    "-DCMAKE_BUILD_TYPE=Release", "-DRMB_NATIVE_BUILD_ID=${buildId.get()}",
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outputDirectory.get().asFile.resolve(abi).invariantSeparatorsPath}")
            }
            execOperations.exec { commandLine(cmakeBin.resolve("cmake.exe"), "--build", build, "--parallel", "8") }
            listOf("avutil", "avcodec", "avformat").forEach { library ->
                root.resolve("app/build/ffmpeg/$abi/install/lib/lib$library.so")
                    .copyTo(outputDirectory.get().asFile.resolve("$abi/lib$library.so"), overwrite = true)
            }
        }
    }
}
val ffmpegBuild = tasks.register<Exec>("buildFfmpeg") {
    inputs.file(rootProject.file("scripts/build-ffmpeg.sh"))
    inputs.dir(rootProject.file("third_party/ffmpeg"))
    outputs.dir(layout.buildDirectory.dir("ffmpeg"))
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("gitBash").orElse("C:/Program Files/Git/bin/bash.exe").get(), "scripts/build-ffmpeg.sh")
}
val legacyNative = tasks.register<LegacyNativeBuild>("buildLegacyNative") {
    dependsOn(ffmpegBuild)
    mustRunAfter(prepareLegacyNdk)
    ndkDirectory.set(legacyNdkDirectory)
    sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
    rootDirectory.set(rootProject.layout.projectDirectory)
    sources.from(nativeInputs)
    buildId.set(nativeBuildId)
    outputDirectory.set(layout.buildDirectory.dir("generated/legacyJniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(legacyNative, LegacyNativeBuild::outputDirectory)
}

android {
    namespace = "com.retromobilebench"
    compileSdk = 37
    // AGP uses the installed modern tools only to strip/package prebuilt JNI libs.
    // Compilation is exclusively handled by buildLegacyNative with r10e.
    ndkVersion = "23.2.8568313"
    packaging.jniLibs.useLegacyPackaging = true


    defaultConfig {
        applicationId = "com.retromobilebench"
        minSdk = 8
        buildConfigField("String", "NATIVE_BUILD_ID", "\"${nativeBuildId.get()}\"")
        // 23: lowest target Android 14+ will install; no behaviour change on 4.x (README "发布方式").
        targetSdk = 23
        versionCode = 1
        versionName = "0.0.1"
        // Framework Instrumentation avoids newer AndroidX runtime requirements.
        testInstrumentationRunner = "com.retromobilebench.BackendInstrumentation"

        ndk {
            abiFilters += listOf("armeabi-v7a", "x86")
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // D8 desugars language features for the API 8 runtime; API lint stays fatal.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Froyo only understands JAR/v1 signatures, including future release keys.
    signingConfigs.configureEach { enableV1Signing = true }

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
    mustRunAfter(prepareLegacyNdk)
    noticeFiles.from(
        rootProject.file("third_party/coremark/LICENSE.md"),
        rootProject.file("third_party/mbedtls/LICENSE"),
        rootProject.file("third_party/libjpeg-turbo/LICENSE.md"),
        rootProject.file("third_party/libpng/LICENSE"),
        rootProject.file("third_party/ffmpeg/COPYING.LGPLv2.1"),
        rootProject.file("docs/media-distribution.md"),
        rootProject.file("app/src/main/assets/media/manifest.json"),
        legacyNdkDirectory.file("build/tools/toolchain-licenses/COPYING3"),
        legacyNdkDirectory.file("build/tools/toolchain-licenses/COPYING.RUNTIME"),
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

// Reproducible fixed ETC1 scene pack in the default APK. Diagnostic data is developer-only.
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
        val tests = if (fullPack.get()) listOf("gl_egypt_off", "gl_egypt", "gl_trex_off", "gl_trex", "gl_fill_off") else listOf("gl_alu_off", "gl_fill_off", "gl_driver_off", "gl_blending_off")
        tests.forEach { test -> selected["config/$test.json"] = source.resolve("gfxbench40/resources/gfxbench_gl/config/$test.json") }
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
            "tests" to tests, "files" to manifestFiles,
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
        include("gfxbench40/resources/gfxbench_gl/config/gl_*.json")
        include("gfxbench40/resources/data/gfx/shaders_40/lowlevel/**")
        include("gfxbench40/resources/data/gfx/shaders_40/shaders.20/**")
        include("gfxbench-data40/data/gfx/common/**", "gfxbench-data40/data/gfx/fill/**")
        include("gfxbench-data40/data/gfx/trex/**", "gfxbench-data40/data/gfx/egypt/**")
    })
    fullPack.set(providers.gradleProperty("gpuData").map { it != "diagnostic" }.orElse(true))
    outputDirectory.set(layout.buildDirectory.dir("generated/gpuPack"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(gpuPack, GpuPackTask::outputDirectory)
}

apply(from = "corresponding-source.gradle.kts")

dependencies {
    // Framework-only UI and tests preserve the API 8 baseline.
}
