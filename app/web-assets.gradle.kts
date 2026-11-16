import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.security.MessageDigest
import groovy.json.JsonOutput

// Original synthetic assets, recipe web-corpus-v1. No downloaded web pages or frameworks.
abstract class WebCorpusTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protocolFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recipeFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFile: RegularFileProperty
    @TaskAction fun generate() {
        val root = outputDirectory.get().asFile.resolve("web")
        root.deleteRecursively()
        root.mkdirs()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Exactly 256 ASCII characters, two phrases and ten keyword occurrences per paragraph.
        val prefix = "daily web daily web " + (0 until 10).joinToString(" ") { "task" } + " "
        val paragraph = prefix + "abcdefghijklmnopqrstuvwxyz ".repeat(10).take(256 - prefix.length)
        check(paragraph.length == 256)
        root.resolve("corpus.js").writeText("window.RMB_CORPUS=" + JsonOutput.toJson(linkedMapOf(
            "paragraph" to paragraph)) + ";\n", Charsets.UTF_8)
        for (index in 0 until 6) {
            val image = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 180) for (x in 0 until 320) {
                val r = (x * (index + 1) + y * 3) and 255
                val g = (y * (index + 2) + x / 8 * 17) and 255
                val b = ((x xor y) + index * 37) and 255
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
            val extension = if (index < 4) "jpg" else "png"
            check(ImageIO.write(image, extension, root.resolve("image$index.$extension")))
        }
        val lock = groovy.json.JsonSlurper().parse(lockFile.get().asFile) as Map<*, *>
        (lock["files"] as List<*>).forEach { entry ->
            val item = entry as Map<*, *>; val bytes = root.resolve(item["path"] as String).readBytes()
            check(bytes.size.toLong() == (item["bytes"] as Number).toLong() && sha(bytes) == item["sha256"]) {
                "Web corpus differs from lock: ${item["path"]} (bytes=${bytes.size}, sha256=${sha(bytes)}). Use the prescribed JBR/ImageIO or review a new corpus version."
            }
        }
        val files = (root.listFiles()!!.filter { it.name != "manifest.json" } + protocolFiles.files).sortedBy { it.name }
        val manifest = linkedMapOf("protocol" to "web-daily-v1", "generator" to "web-corpus-v1",
            "recipe_sha256" to sha(recipeFile.get().asFile.readBytes()),
            "origin" to "Original RetroMobileBench synthetic corpus and fixed 480p/720p/1080p image set.",
            "files" to files.map { mapOf("path" to it.name, "bytes" to it.length(), "sha256" to sha(it.readBytes())) })
        root.resolve("manifest.json").writeText(JsonOutput.toJson(manifest), Charsets.UTF_8)
    }
}
val webCorpus = tasks.register<WebCorpusTask>("generateWebCorpus") {
    recipeFile.set(layout.projectDirectory.file("web-assets.gradle.kts"))
    lockFile.set(layout.projectDirectory.file("web-corpus-lock.json"))
    protocolFiles.from(fileTree("src/main/assets/web") { include("*.html", "*.js", "*.jpg", "*.png") })
    outputDirectory.set(layout.buildDirectory.dir("generated/webCorpus"))
}
