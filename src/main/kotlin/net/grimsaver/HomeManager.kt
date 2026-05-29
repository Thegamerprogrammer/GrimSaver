package net.grimsaver

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class HomeManager(private val logger: LastStandLogger) {
    private val directory: Path = FabricLoader.getInstance().configDir.resolve("LastStand").resolve("DangerHomes")
    private val threatCooldowns = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGlobalTrigger = 0L

    fun cooldownReady(threat: Threat): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGlobalTrigger < GrimSaverConfig.globalCooldownMillis) return false
        val lastThreat = threatCooldowns[threat.cooldownKey] ?: 0L
        return now - lastThreat >= GrimSaverConfig.perThreatCooldownMillis
    }

    fun tryTrigger(client: Minecraft, threat: Threat): SavedHome? {
        val player = client.player ?: return null
        if (!cooldownReady(threat)) return null
        if (!directory.exists()) directory.createDirectories()

        val file = fileForServer(client.currentServer?.ip ?: "singleplayer")
        val next = nextDeathNumber(file)
        val homeName = "death$next"
        val savedHome = SavedHome(
            name = homeName,
            timestamp = Instant.now(),
            position = player.position(),
            reason = threat.reason,
            damage = threat.damage,
            threatKind = threat.kind,
            source = threat.source
        )
        appendRecord(file, savedHome)
        logger.log(savedHome)

        lastGlobalTrigger = System.currentTimeMillis()
        threatCooldowns[threat.cooldownKey] = lastGlobalTrigger
        player.connection.sendCommand("sethome $homeName")
        return savedHome
    }

    fun recentHomes(client: Minecraft, limit: Int = 10): List<SavedHome> =
        readHomes(fileForServer(client.currentServer?.ip ?: "singleplayer")).takeLast(limit).asReversed()

    private fun fileForServer(server: String): Path {
        if (!directory.exists()) directory.createDirectories()
        return directory.resolve(sanitizeServer(server))
    }

    private fun nextDeathNumber(file: Path): Int {
        if (!file.exists()) return 1
        val maxExisting = Files.readAllLines(file).mapNotNull { line ->
            Regex("death(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return maxExisting + 1
    }

    private fun readHomes(file: Path): List<SavedHome> {
        if (!file.exists()) return emptyList()
        return Files.readAllLines(file).mapNotNull(::parseRecord)
    }

    private fun appendRecord(file: Path, home: SavedHome) {
        val line = listOf(
            home.name,
            home.timestamp.toString(),
            "${home.position.x},${home.position.y},${home.position.z}",
            home.threatKind.id,
            home.damage.toString(),
            home.source.replace('|', '/').replace('\n', ' '),
            home.reason.replace('|', '/').replace('\n', ' ')
        ).joinToString("|") + System.lineSeparator()
        Files.writeString(file, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    private fun parseRecord(line: String): SavedHome? {
        val parts = line.split('|')
        if (parts.size >= 7) {
            val pos = parts[2].split(',').mapNotNull { it.toDoubleOrNull() }
            if (pos.size == 3) {
                val kind = ThreatKind.entries.firstOrNull { it.id == parts[3] } ?: ThreatKind.LETHAL_PROJECTILE
                return SavedHome(
                    name = parts[0],
                    timestamp = Instant.parse(parts[1]),
                    position = Vec3(pos[0], pos[1], pos[2]),
                    threatKind = kind,
                    damage = parts[4].toDoubleOrNull() ?: 0.0,
                    source = parts[5],
                    reason = parts[6]
                )
            }
        }

        val name = Regex("death\\d+").find(line)?.value ?: return null
        val coords = Regex("x=([\\d.-]+) y=([\\d.-]+) z=([\\d.-]+)").find(line)?.groupValues
        val pos = if (coords != null) Vec3(coords[1].toDouble(), coords[2].toDouble(), coords[3].toDouble()) else Vec3.ZERO
        return SavedHome(name, Instant.EPOCH, pos, line.substringAfter("threat=", "Saved Home"), 0.0, ThreatKind.LETHAL_PROJECTILE, "legacy")
    }

    private fun sanitizeServer(server: String): String = server
        .trim()
        .ifBlank { "unknown" }
        .replace(':', '_')
        .replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
}
