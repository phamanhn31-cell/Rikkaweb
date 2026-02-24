package me.rerere.rikkahub.standalone.test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object Evidence {
    private fun dir(): Path {
        var p = Paths.get("").toAbsolutePath()
        repeat(10) {
            if (Files.exists(p.resolve("settings.gradle.kts"))) {
                return p.resolve(".sisyphus").resolve("evidence")
            }
            p = p.parent ?: return@repeat
        }
        return Paths.get(".sisyphus", "evidence")
    }

    fun write(filename: String, content: String) {
        val d = dir()
        Files.createDirectories(d)
        Files.writeString(d.resolve(filename), content, StandardCharsets.UTF_8)
    }
}
