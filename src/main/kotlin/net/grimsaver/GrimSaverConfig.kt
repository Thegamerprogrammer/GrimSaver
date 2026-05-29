package net.grimsaver

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object GrimSaverConfig {
    var enabled = true
    var projectileThreats = true
    var pvpThreats = true
    var mobThreats = true
    var fallThreats = true
    var combineThreats = true
    var lethalThreshold = 1.0
    var lethalConfidenceThreshold = 0.95
    var safetyMargin = 2.0
    var scanEveryTicks = 2
    var scanRadius = 96.0
    var globalCooldownMillis = 4_000L
    var perThreatCooldownMillis = 1_500L
    var minCommandIntervalMillis = 10_000L
    var threatResetTimeoutMillis = 30_000L
    var projectileLookaheadTicks = 80
    var meleeRangePadding = 0.75
    var fallDamageMargin = 0.5
    var loggingVerbosity = LoggingVerbosity.WARN

    @Deprecated("Use pvpThreats and mobThreats for more precise control.")
    var meleeThreats: Boolean
        get() = pvpThreats || mobThreats
        set(value) {
            pvpThreats = value
            mobThreats = value
        }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val directory: Path = FabricLoader.getInstance().configDir.resolve("grimsaver")
    private val path: Path = directory.resolve("config.json")
    private val legacyPath: Path = FabricLoader.getInstance().configDir.resolve("LastStand").resolve("grimsaver.properties")

    fun load() {
        runCatching {
            if (!directory.exists()) directory.createDirectories()
            if (!path.exists()) {
                legacyPath.takeIf { it.exists() }?.let(::loadLegacyProperties)
                save()
                return
            }

            val json = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
            enabled = json.bool("enabled", enabled)
            projectileThreats = json.bool("projectileThreats", projectileThreats)
            pvpThreats = json.bool("pvpThreats", json.bool("meleeThreats", pvpThreats))
            mobThreats = json.bool("mobThreats", json.bool("meleeThreats", mobThreats))
            fallThreats = json.bool("fallThreats", fallThreats)
            combineThreats = json.bool("combineThreats", combineThreats)
            lethalThreshold = json.double("lethalThreshold", lethalThreshold).coerceIn(1.0, 3.0)
            lethalConfidenceThreshold = json.double("lethalConfidenceThreshold", lethalConfidenceThreshold).coerceIn(0.5, 1.0)
            safetyMargin = json.double("safetyMargin", safetyMargin).coerceAtLeast(0.0)
            scanEveryTicks = json.int("scanEveryTicks", scanEveryTicks).coerceAtLeast(1)
            scanRadius = json.double("scanRadius", scanRadius).coerceIn(8.0, 256.0)
            globalCooldownMillis = json.long("globalCooldownMillis", globalCooldownMillis).coerceAtLeast(0L)
            perThreatCooldownMillis = json.long("perThreatCooldownMillis", perThreatCooldownMillis).coerceAtLeast(0L)
            minCommandIntervalMillis = json.long("minCommandIntervalMillis", minCommandIntervalMillis).coerceAtLeast(0L)
            threatResetTimeoutMillis = json.long("threatResetTimeoutMillis", threatResetTimeoutMillis).coerceAtLeast(1_000L)
            projectileLookaheadTicks = json.int("projectileLookaheadTicks", projectileLookaheadTicks).coerceIn(10, 200)
            meleeRangePadding = json.double("meleeRangePadding", meleeRangePadding).coerceIn(0.0, 8.0)
            fallDamageMargin = json.double("fallDamageMargin", fallDamageMargin).coerceIn(0.0, 8.0)
            loggingVerbosity = LoggingVerbosity.fromConfig(json.string("loggingVerbosity", loggingVerbosity.name))
            save()
        }.onFailure { throwable ->
            warnGrimSaverFailure("config-load", "Unable to load GrimSaver config; using in-memory defaults", throwable)
        }
    }

    fun save() {
        if (!directory.exists()) directory.createDirectories()
        val dto = ConfigDto(
            enabled = enabled,
            projectileThreats = projectileThreats,
            pvpThreats = pvpThreats,
            mobThreats = mobThreats,
            fallThreats = fallThreats,
            combineThreats = combineThreats,
            lethalThreshold = lethalThreshold,
            lethalConfidenceThreshold = lethalConfidenceThreshold,
            safetyMargin = safetyMargin,
            scanEveryTicks = scanEveryTicks,
            scanRadius = scanRadius,
            globalCooldownMillis = globalCooldownMillis,
            perThreatCooldownMillis = perThreatCooldownMillis,
            minCommandIntervalMillis = minCommandIntervalMillis,
            threatResetTimeoutMillis = threatResetTimeoutMillis,
            projectileLookaheadTicks = projectileLookaheadTicks,
            meleeRangePadding = meleeRangePadding,
            fallDamageMargin = fallDamageMargin,
            loggingVerbosity = loggingVerbosity.name.lowercase()
        )
        Files.newBufferedWriter(path).use { gson.toJson(dto, it) }
    }

    fun warnLoggingEnabled(): Boolean = loggingVerbosity != LoggingVerbosity.OFF
    fun debugLoggingEnabled(): Boolean = loggingVerbosity == LoggingVerbosity.DEBUG

    private fun loadLegacyProperties(file: Path) {
        val props = Properties()
        Files.newInputStream(file).use(props::load)
        enabled = props.bool("enabled", enabled)
        projectileThreats = props.bool("projectileThreats", projectileThreats)
        meleeThreats = props.bool("meleeThreats", meleeThreats)
        fallThreats = props.bool("fallThreats", fallThreats)
        combineThreats = props.bool("combineThreats", combineThreats)
        safetyMargin = props.double("safetyMargin", safetyMargin)
        scanEveryTicks = props.int("scanEveryTicks", scanEveryTicks).coerceAtLeast(1)
        globalCooldownMillis = props.long("globalCooldownMillis", globalCooldownMillis)
        perThreatCooldownMillis = props.long("perThreatCooldownMillis", perThreatCooldownMillis)
        projectileLookaheadTicks = props.int("projectileLookaheadTicks", projectileLookaheadTicks).coerceIn(10, 200)
        meleeRangePadding = props.double("meleeRangePadding", meleeRangePadding)
        fallDamageMargin = props.double("fallDamageMargin", fallDamageMargin)
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toBooleanStrictOrNull() ?: default
    private fun JsonObject.double(key: String, default: Double): Double = get(key)?.takeIf { it.isJsonPrimitive }?.asDoubleOrNull() ?: default
    private fun JsonObject.int(key: String, default: Int): Int = get(key)?.takeIf { it.isJsonPrimitive }?.asIntOrNull() ?: default
    private fun JsonObject.long(key: String, default: Long): Long = get(key)?.takeIf { it.isJsonPrimitive }?.asLongOrNull() ?: default
    private fun JsonObject.string(key: String, default: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default

    private fun Properties.bool(key: String, default: Boolean) = getProperty(key)?.toBooleanStrictOrNull() ?: default
    private fun Properties.double(key: String, default: Double) = getProperty(key)?.toDoubleOrNull() ?: default
    private fun Properties.int(key: String, default: Int) = getProperty(key)?.toIntOrNull() ?: default
    private fun Properties.long(key: String, default: Long) = getProperty(key)?.toLongOrNull() ?: default

    private fun com.google.gson.JsonElement.asDoubleOrNull(): Double? = runCatching { asDouble }.getOrNull()
    private fun com.google.gson.JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()
    private fun com.google.gson.JsonElement.asLongOrNull(): Long? = runCatching { asLong }.getOrNull()

    enum class LoggingVerbosity {
        OFF,
        WARN,
        DEBUG;

        companion object {
            fun fromConfig(value: String): LoggingVerbosity = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WARN
        }
    }

    private data class ConfigDto(
        val enabled: Boolean,
        val projectileThreats: Boolean,
        val pvpThreats: Boolean,
        val mobThreats: Boolean,
        val fallThreats: Boolean,
        val combineThreats: Boolean,
        val lethalThreshold: Double,
        val lethalConfidenceThreshold: Double,
        val safetyMargin: Double,
        val scanEveryTicks: Int,
        val scanRadius: Double,
        val globalCooldownMillis: Long,
        val perThreatCooldownMillis: Long,
        val minCommandIntervalMillis: Long,
        val threatResetTimeoutMillis: Long,
        val projectileLookaheadTicks: Int,
        val meleeRangePadding: Double,
        val fallDamageMargin: Double,
        val loggingVerbosity: String
    )
}
