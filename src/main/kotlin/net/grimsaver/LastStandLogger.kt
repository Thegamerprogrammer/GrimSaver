package net.grimsaver

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class LastStandLogger {
    private val directory: Path = FabricLoader.getInstance().configDir.resolve("LastStand")
    private val logFile: Path = directory.resolve("logs.txt")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    @Synchronized
    fun log(savedHome: SavedHome) {
        if (!directory.exists()) directory.createDirectories()
        val line = "[${formatter.format(savedHome.timestamp)}] ${savedHome.name} | Reason: ${savedHome.reason} | " +
            "Damage: ${"%.1f".format(Locale.ROOT, savedHome.damage)} | " +
            "Pos: ${"%.2f".format(Locale.ROOT, savedHome.position.x)} " +
            "${"%.2f".format(Locale.ROOT, savedHome.position.y)} " +
            "${"%.2f".format(Locale.ROOT, savedHome.position.z)}${System.lineSeparator()}"
        Files.writeString(logFile, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}
