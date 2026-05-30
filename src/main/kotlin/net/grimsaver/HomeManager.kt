package net.grimsaver

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class HomeManager(private val logger: LastStandLogger) {
    private val directory: Path = FabricLoader.getInstance().configDir.resolve("grimsaver").resolve("homes")
    private val legacyDirectory: Path = FabricLoader.getInstance().configDir.resolve("LastStand").resolve("DangerHomes")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val threatCooldowns = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGlobalTrigger = 0L
    private val deleteExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GrimSaver-HomeLifecycle").apply { isDaemon = true }
    }

    fun cooldownReady(threat: Threat): Boolean {
        val now = System.currentTimeMillis()
        val globalDelay = if (threat.kind.isEmergency()) {
            maxOf(GrimSaverConfig.minCommandIntervalMillis, GrimSaverConfig.criticalEmergencyCooldownMillis)
        } else {
            maxOf(GrimSaverConfig.globalCooldownMillis, GrimSaverConfig.minCommandIntervalMillis)
        }
        if (now - lastGlobalTrigger < globalDelay) return false
        val lastThreat = threatCooldowns[threat.cooldownKey] ?: 0L
        return now - lastThreat >= GrimSaverConfig.perThreatCooldownMillis
    }

    @Synchronized
    fun tryTrigger(client: Minecraft, threat: Threat): SavedHome? {
        val player = client.player ?: return null
        if (!cooldownReady(threat)) return null
        if (!directory.exists()) directory.createDirectories()

        val serverKey = client.currentServer?.ip ?: "singleplayer"
        val file = fileForServer(serverKey)
        val state = readState(file, serverKey)
        val next = nextDeathNumber(state)
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
        val updated = state.withHome(savedHome, next)
        writeState(file, updated)
        logger.log(savedHome)

        lastGlobalTrigger = System.currentTimeMillis()
        threatCooldowns[threat.cooldownKey] = lastGlobalTrigger
        player.connection.sendCommand("sethome $homeName")
        debugGrimSaver(
            "Lethal threat triggered /sethome {}: damage={} effectiveHp={} confidence={} source={}",
            homeName,
            threat.damage,
            threat.health,
            threat.confidence,
            threat.source
        )
        return savedHome
    }

    fun recentHomes(client: Minecraft, limit: Int = 10): List<SavedHome> =
        readState(fileForServer(client.currentServer?.ip ?: "singleplayer"), client.currentServer?.ip ?: "singleplayer")
            .records
            .mapNotNull { it.toSavedHome() }
            .takeLast(limit)
            .asReversed()

    private fun fileForServer(server: String): Path {
        if (!directory.exists()) directory.createDirectories()
        return directory.resolve(sanitizeServer(server) + ".json")
    }

    private fun readState(file: Path, server: String): HomeState {
        if (file.exists()) {
            runCatching {
                val parsed = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
                val homes = parsed.getAsJsonArray("homes")?.mapNotNull { element ->
                    runCatching { element.asString }.getOrNull()?.takeIf { it.startsWith("death") }
                }?.toMutableSet() ?: mutableSetOf()
                val records = parsed.getAsJsonArray("records")?.mapNotNull { element ->
                    runCatching { gson.fromJson(element, SavedHomeRecord::class.java) }.getOrNull()
                }?.filter { it.name.startsWith("death") } ?: emptyList()
                homes += records.map { it.name }
                val lastDeathNumber = maxOf(
                    parsed.get("lastDeathNumber")?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
                    homes.maxDeathNumber()
                )
                val nextSlot = parsed.get("nextSlot")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 4) ?: ((lastDeathNumber % GrimSaverConfig.homeMaxSlots.coerceIn(1, 4)) + 1)
                return HomeState(lastDeathNumber, nextSlot, homes.filter { slotNumber(it) in 1..GrimSaverConfig.homeMaxSlots.coerceIn(1, 4) }.toSet(), records.filter { slotNumber(it.name) in 1..GrimSaverConfig.homeMaxSlots.coerceIn(1, 4) })
            }.onFailure { throwable ->
                warnGrimSaverFailure("homes-read-${file.fileName}", "Malformed GrimSaver home file; preserving it and starting with empty state", throwable)
            }
        }

        return readLegacyState(server)
    }

    private fun readLegacyState(server: String): HomeState {
        val legacyFile = legacyDirectory.resolve(sanitizeServer(server) + ".txt")
        if (!legacyFile.exists()) return HomeState()
        return runCatching {
            val records = Files.readAllLines(legacyFile).mapNotNull(::parseLegacyRecord)
            val homes = records.map { it.name }.toMutableSet()
            HomeState(homes.maxDeathNumber(), ((homes.maxDeathNumber() % GrimSaverConfig.homeMaxSlots.coerceIn(1, 4)) + 1), homes, records)
        }.getOrElse { throwable ->
            warnGrimSaverFailure("legacy-homes-read-${legacyFile.fileName}", "Unable to read legacy GrimSaver home file; ignoring legacy state", throwable)
            HomeState()
        }
    }

    private fun nextDeathNumber(state: HomeState): Int {
        val maxSlots = GrimSaverConfig.homeMaxSlots.coerceIn(1, 4)
        if (GrimSaverConfig.homeUseMaxSlot) return maxSlots
        val next = (state.nextSlot.takeIf { it in 1..maxSlots } ?: 1)
        return next
    }

    fun observeHomeCommand(client: Minecraft, command: String) {
        val trimmed = command.trim().removePrefix("/")
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2 || !parts[0].equals("home", ignoreCase = true)) return
        val home = normalizeHomeName(parts[1]) ?: return
        scheduleDelete(client, home)
    }

    private fun normalizeHomeName(token: String): String? {
        val numeric = token.toIntOrNull()?.takeIf { it in 1..4 }?.let { "death$it" }
        val named = token.takeIf { DEATH_NAME.matches(it) }
        return numeric ?: named
    }

    private fun scheduleDelete(client: Minecraft, homeName: String) {
        if (!GrimSaverConfig.homeAutoDelete) return
        val delay = GrimSaverConfig.homeDeleteDelayMillis.coerceAtLeast(0L)
        deleteExecutor.schedule({
            client.execute {
                runCatching {
                    client.player?.connection?.sendCommand("delhome $homeName")
                    markHomeDeleted(client, homeName)
                    debugGrimSaver("Deleted GrimSaver home {} after lifecycle delay {}ms", homeName, delay)
                }.onFailure { throwable ->
                    warnGrimSaverFailure("home-auto-delete", "Unable to auto-delete GrimSaver home $homeName", throwable)
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun markHomeDeleted(client: Minecraft, homeName: String) {
        val serverKey = client.currentServer?.ip ?: "singleplayer"
        val file = fileForServer(serverKey)
        val state = readState(file, serverKey)
        writeState(file, state.withoutHome(homeName))
    }

    fun shutdown() {
        deleteExecutor.shutdownNow()
    }

    private fun writeState(file: Path, state: HomeState) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { writer ->
            gson.toJson(state.toDto(), writer)
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseLegacyRecord(line: String): SavedHomeRecord? {
        parseDelimitedRecord(line)?.let { return it }
        val name = DEATH_NAME.find(line)?.value ?: return null
        val coords = LEGACY_COORDS.find(line)?.groupValues
        val position = if (coords != null) PositionDto(coords[1].toDouble(), coords[2].toDouble(), coords[3].toDouble()) else PositionDto.ZERO
        return SavedHomeRecord(
            name = name,
            timestamp = Instant.EPOCH.toString(),
            position = position,
            threatKind = ThreatKind.LETHAL_PROJECTILE.id,
            damage = 0.0,
            source = "legacy",
            reason = line.substringAfter("threat=", "Saved Home")
        )
    }

    private fun parseDelimitedRecord(line: String): SavedHomeRecord? {
        val parts = line.split('|')
        if (parts.size < 7) return null
        val pos = parts[2].split(',').mapNotNull { it.toDoubleOrNull() }
        if (pos.size != 3) return null
        return SavedHomeRecord(
            name = parts[0],
            timestamp = runCatching { Instant.parse(parts[1]).toString() }.getOrDefault(Instant.EPOCH.toString()),
            position = PositionDto(pos[0], pos[1], pos[2]),
            threatKind = parts[3],
            damage = parts[4].toDoubleOrNull() ?: 0.0,
            source = parts[5],
            reason = parts[6]
        )
    }

    private fun HomeState.withHome(home: SavedHome, deathNumber: Int): HomeState {
        val maxSlots = GrimSaverConfig.homeMaxSlots.coerceIn(1, 4)
        val newHomes = homes.filterTo(mutableSetOf()) { it != home.name && slotNumber(it) in 1..maxSlots }
        newHomes += home.name
        val newRecords = records.filter { it.name != home.name && slotNumber(it.name) in 1..maxSlots } + home.toRecord()
        val next = if (GrimSaverConfig.homeUseMaxSlot) maxSlots else (deathNumber % maxSlots) + 1
        return copy(lastDeathNumber = deathNumber, nextSlot = next, homes = newHomes, records = newRecords)
    }

    private fun HomeState.withoutHome(homeName: String): HomeState {
        val released = slotNumber(homeName).takeIf { it in 1..GrimSaverConfig.homeMaxSlots.coerceIn(1, 4) }
        return copy(
            nextSlot = if (!GrimSaverConfig.homeUseMaxSlot && released != null) released else nextSlot,
            homes = homes - homeName,
            records = records.filterNot { it.name == homeName }
        )
    }

    private fun slotNumber(name: String): Int = DEATH_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun HomeState.toDto(): HomeStateDto = HomeStateDto(lastDeathNumber, nextSlot, homes.sortedWith(DEATH_NAME_COMPARATOR), records)

    private fun SavedHome.toRecord(): SavedHomeRecord = SavedHomeRecord(
        name = name,
        timestamp = timestamp.toString(),
        position = PositionDto(position.x, position.y, position.z),
        threatKind = threatKind.id,
        damage = damage,
        source = source,
        reason = reason
    )

    private fun SavedHomeRecord.toSavedHome(): SavedHome? = runCatching {
        SavedHome(
            name = name,
            timestamp = Instant.parse(timestamp),
            position = Vec3(position.x, position.y, position.z),
            threatKind = ThreatKind.entries.firstOrNull { it.id == threatKind } ?: ThreatKind.LETHAL_PROJECTILE,
            damage = damage,
            source = source,
            reason = reason
        )
    }.getOrNull()

    private fun Set<String>.maxDeathNumber(): Int = maxOfOrNull { home ->
        DEATH_NAME.matchEntire(home)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    } ?: 0

    private fun ThreatKind.isEmergency(): Boolean = when (this) {
        ThreatKind.CRITICAL_HEALTH,
        ThreatKind.CREEPER_EMERGENCY,
        ThreatKind.SWARM_EMERGENCY,
        ThreatKind.DEATH_FAILSAFE,
        ThreatKind.CRITICAL_DAMAGE,
        ThreatKind.BURST_DAMAGE,
        ThreatKind.PASSIVE_ENTITY_DANGER -> true
        else -> false
    }

    private fun sanitizeServer(server: String): String = server
        .trim()
        .ifBlank { "unknown" }
        .replace(':', '_')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")

    private data class HomeState(
        val lastDeathNumber: Int = 0,
        val nextSlot: Int = 1,
        val homes: Set<String> = emptySet(),
        val records: List<SavedHomeRecord> = emptyList()
    )

    private data class HomeStateDto(
        val lastDeathNumber: Int,
        val nextSlot: Int,
        val homes: List<String>,
        val records: List<SavedHomeRecord>
    )

    private data class SavedHomeRecord(
        val name: String = "",
        val timestamp: String = Instant.EPOCH.toString(),
        val position: PositionDto = PositionDto.ZERO,
        val threatKind: String = ThreatKind.LETHAL_PROJECTILE.id,
        val damage: Double = 0.0,
        val source: String = "unknown",
        val reason: String = "Saved Home"
    )

    private data class PositionDto(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
        companion object {
            val ZERO = PositionDto()
        }
    }

    private companion object {
        val DEATH_NAME = Regex("death(\\d+)")
        val LEGACY_COORDS = Regex("x=([\\d.-]+) y=([\\d.-]+) z=([\\d.-]+)")
        val DEATH_NAME_COMPARATOR = compareBy<String> { DEATH_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
    }
}
