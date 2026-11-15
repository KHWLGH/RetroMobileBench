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
        val root = outputDirectory.get().asFile.resolve("web"); root.mkdirs()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var seed = 101010L
        fun next(): Long { seed = (1664525L * seed + 1013904223L) and 0xffffffffL; return seed }
        fun ascii(length: Int): String = (0 until length).map { ('a'.code + (next() % 26).toInt()).toChar() }.joinToString("")
        val rows = (0 until 1000).map { id -> linkedMapOf<String, Any>("id" to id, "group" to id % 10,
            "value" to (next() % 100000).toInt(), "price" to (next() % 10000).toInt(),
            "enabled" to (next() % 2 == 0L), "name" to ascii(32), "description" to ascii(96), "weight" to (next() % 1000).toInt()) }
        val json = JsonOutput.toJson(rows)
        val selected = rows.filter { (it["group"] as Int) < 5 }.sortedWith(compareBy({ it["value"] as Int }, { it["id"] as Int }))
        val serialized = JsonOutput.toJson(selected)
        fun checksum(text: String): Long { var hash = 0L; text.forEach { hash = (hash * 31 + it.code) % 2147483647 }; return hash }
        // Exactly 256 ASCII characters, two phrases and ten keyword occurrences per paragraph.
        val prefix = "daily web daily web " + (0 until 10).joinToString(" ") { "task" } + " "
        val paragraph = prefix + "abcdefghijklmnopqrstuvwxyz ".repeat(10).take(256 - prefix.length)
        check(paragraph.length == 256)
        root.resolve("data.json").writeText(json, Charsets.UTF_8)
        root.resolve("text.txt").writeText((0 until 24).joinToString("\n") { paragraph }, Charsets.UTF_8)
        root.resolve("corpus.js").writeText("window.RMB_CORPUS=" + JsonOutput.toJson(linkedMapOf(
            "json" to json, "paragraph" to paragraph, "selected_ids" to selected.map { it["id"] },
            "serialized_length" to serialized.length, "serialized_checksum" to checksum(serialized))) + ";\n", Charsets.UTF_8)
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
        check((lock["serialized_length"] as Number).toInt() == serialized.length && (lock["serialized_checksum"] as Number).toLong() == checksum(serialized)) { "Web expected results changed; review and version the protocol" }
        (lock["files"] as List<*>).forEach { entry ->
            val item = entry as Map<*, *>; val bytes = root.resolve(item["path"] as String).readBytes()
            check(bytes.size.toLong() == (item["bytes"] as Number).toLong() && sha(bytes) == item["sha256"]) { "Web corpus differs from lock: ${item["path"]}. Use the prescribed JBR/ImageIO or review a new corpus version." }
        }
        val files = (root.listFiles()!!.filter { it.name != "manifest.json" } + protocolFiles.files).sortedBy { it.name }
        val manifest = linkedMapOf("protocol" to "web-daily-v2", "generator" to "web-corpus-v1", "seed" to 101010,
            "recipe_sha256" to sha(recipeFile.get().asFile.readBytes()),
            "origin" to "Original RetroMobileBench synthetic corpus; no third-party source assets.",
            "expected" to mapOf("records" to 1000, "selected" to 500, "display_rows" to 100,
                "serialized_length" to serialized.length, "serialized_checksum" to checksum(serialized)),
            "files" to files.map { mapOf("path" to it.name, "bytes" to it.length(), "sha256" to sha(it.readBytes())) })
        root.resolve("manifest.json").writeText(JsonOutput.toJson(manifest), Charsets.UTF_8)
    }
}
val webCorpus = tasks.register<WebCorpusTask>("generateWebCorpus") {
    recipeFile.set(layout.projectDirectory.file("web-assets.gradle.kts"))
    lockFile.set(layout.projectDirectory.file("web-corpus-lock.json"))
    protocolFiles.from(fileTree("src/main/assets/web") { include("*.html", "*.js") })
    outputDirectory.set(layout.buildDirectory.dir("generated/webCorpus"))
}
