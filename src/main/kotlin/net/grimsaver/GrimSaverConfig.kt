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
    var criticalHealthEmergency = true
    var criticalHealthHearts = 4.0
    var ultraCriticalHearts = 2.0
    var swarmTriggerEnabled = true
    var swarmMobThreshold = 6
    var swarmHealthThreshold = 6.0
    var creeperEmergencyEnabled = true
    var recentDamageWindowTicks = 20
    var criticalEmergencyCooldownMillis = 15_000L
    var burstDamageEnabled = true
    var burstDamagePercentThreshold = 0.5
    var deathFailsafeEnabled = true
    var ultraCriticalAnyDamage = true
    var loggingVerbosity = LoggingVerbosity.WARN

    var riskEngineEnabled = true
    var riskDebug = false
    var burstAbsoluteThreshold = 6.0
    var burstVelocityThreshold = 12.0
    var lethalBurstVelocityThreshold = 30.0
    var burstMediumThreshold = 6.0
    var burstCriticalThreshold = 10.0
    var burstPercentWindowTicks = 20
    var healthVelocityWeight = 0.40
    var burstWeight = 0.25
    var damagePredictionWeight = 0.15
    var targetingWeight = 0.10
    var enchantmentWeight = 0.05
    var trajectoryWeight = 0.05
    var homeMaxSlots = 1
    var homeUseMaxSlot = false
    var homeAutoDelete = true
    var homeDeleteDelayMillis = 6_000L
    var maceDownwardVelocityThreshold = -0.55
    var maceMinimumHeightAdvantage = 1.5
    var maceFallDamageScale = 1.5
    var maceVelocityDamageScale = 0.35
    var moddedProjectileGravity = 0.03
    var moddedProjectileHitboxRadius = 0.25
    var moddedProjectileDrag = 0.99
    var healthForecastTicks = 80
    var threatCorrelationWindowTicks = 20
    var survivalSimulationTicks = 120
    var survivalSimulationBranches = 384
    var deathProbabilityThreshold = 0.93
    var escapeProbabilityTriggerCeiling = 0.35
    var survivalHealthThreshold = 1.0
    var pendingHomeMonitorMillis = 10_000L

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
            criticalHealthEmergency = json.bool("criticalHealthEmergency", criticalHealthEmergency)
            criticalHealthHearts = json.double("criticalHealthHearts", criticalHealthHearts).coerceIn(0.5, 20.0)
            ultraCriticalHearts = json.double("ultraCriticalHearts", ultraCriticalHearts).coerceIn(0.5, criticalHealthHearts)
            swarmTriggerEnabled = json.bool("swarmTriggerEnabled", swarmTriggerEnabled)
            swarmMobThreshold = json.int("swarmMobThreshold", swarmMobThreshold).coerceAtLeast(1)
            swarmHealthThreshold = json.double("swarmHealthThreshold", swarmHealthThreshold).coerceIn(0.5, 40.0)
            creeperEmergencyEnabled = json.bool("creeperEmergencyEnabled", creeperEmergencyEnabled)
            recentDamageWindowTicks = json.int("recentDamageWindowTicks", recentDamageWindowTicks).coerceIn(1, 200)
            criticalEmergencyCooldownMillis = json.long("criticalEmergencyCooldownMillis", criticalEmergencyCooldownMillis).coerceAtLeast(0L)
            burstDamageEnabled = json.bool("burstDamageEnabled", burstDamageEnabled)
            burstDamagePercentThreshold = json.double("burstDamagePercentThreshold", burstDamagePercentThreshold).coerceIn(0.05, 1.0)
            deathFailsafeEnabled = json.bool("deathFailsafeEnabled", deathFailsafeEnabled)
            ultraCriticalAnyDamage = json.bool("ultraCriticalAnyDamage", ultraCriticalAnyDamage)
            loggingVerbosity = LoggingVerbosity.fromConfig(json.string("loggingVerbosity", loggingVerbosity.name))
            riskEngineEnabled = json.bool("riskEngineEnabled", riskEngineEnabled)
            riskDebug = json.bool("riskDebug", riskDebug)
            burstAbsoluteThreshold = json.double("burstAbsoluteThreshold", burstAbsoluteThreshold).coerceIn(0.5, 40.0)
            burstVelocityThreshold = json.double("burstVelocityThreshold", burstVelocityThreshold).coerceIn(0.5, 120.0)
            lethalBurstVelocityThreshold = json.double("lethalBurstVelocityThreshold", lethalBurstVelocityThreshold).coerceIn(burstVelocityThreshold, 240.0)
            burstMediumThreshold = json.double("burstMediumThreshold", burstMediumThreshold).coerceIn(0.5, 40.0)
            burstCriticalThreshold = json.double("burstCriticalThreshold", burstCriticalThreshold).coerceIn(burstMediumThreshold, 80.0)
            burstPercentWindowTicks = json.int("burstPercentWindowTicks", burstPercentWindowTicks).coerceIn(1, 200)
            healthVelocityWeight = json.double("healthVelocityWeight", healthVelocityWeight).coerceIn(0.0, 1.0)
            burstWeight = json.double("burstWeight", burstWeight).coerceIn(0.0, 1.0)
            damagePredictionWeight = json.double("damagePredictionWeight", damagePredictionWeight).coerceIn(0.0, 1.0)
            targetingWeight = json.double("targetingWeight", targetingWeight).coerceIn(0.0, 1.0)
            enchantmentWeight = json.double("enchantmentWeight", enchantmentWeight).coerceIn(0.0, 1.0)
            trajectoryWeight = json.double("trajectoryWeight", trajectoryWeight).coerceIn(0.0, 1.0)
            homeMaxSlots = json.int("homeMaxSlots", homeMaxSlots).coerceIn(1, 4)
            homeUseMaxSlot = json.bool("homeUseMaxSlot", homeUseMaxSlot)
            homeAutoDelete = json.bool("homeAutoDelete", homeAutoDelete)
            homeDeleteDelayMillis = json.long("homeDeleteDelayMillis", homeDeleteDelayMillis).coerceIn(0L, 60_000L)
            maceDownwardVelocityThreshold = json.double("maceDownwardVelocityThreshold", maceDownwardVelocityThreshold).coerceIn(-5.0, -0.05)
            maceMinimumHeightAdvantage = json.double("maceMinimumHeightAdvantage", maceMinimumHeightAdvantage).coerceIn(0.0, 16.0)
            maceFallDamageScale = json.double("maceFallDamageScale", maceFallDamageScale).coerceIn(0.0, 10.0)
            maceVelocityDamageScale = json.double("maceVelocityDamageScale", maceVelocityDamageScale).coerceIn(0.0, 10.0)
            moddedProjectileGravity = json.double("moddedProjectileGravity", moddedProjectileGravity).coerceIn(0.0, 0.25)
            moddedProjectileHitboxRadius = json.double("moddedProjectileHitboxRadius", moddedProjectileHitboxRadius).coerceIn(0.0, 2.0)
            moddedProjectileDrag = json.double("moddedProjectileDrag", moddedProjectileDrag).coerceIn(0.5, 1.1)
            healthForecastTicks = json.int("healthForecastTicks", healthForecastTicks).coerceIn(20, 200)
            threatCorrelationWindowTicks = json.int("threatCorrelationWindowTicks", threatCorrelationWindowTicks).coerceIn(1, 80)
            survivalSimulationTicks = json.int("survivalSimulationTicks", survivalSimulationTicks).coerceIn(20, 200)
            survivalSimulationBranches = json.int("survivalSimulationBranches", survivalSimulationBranches).coerceIn(64, 2048)
            deathProbabilityThreshold = json.double("deathProbabilityThreshold", deathProbabilityThreshold).coerceIn(0.50, 1.0)
            escapeProbabilityTriggerCeiling = json.double("escapeProbabilityTriggerCeiling", escapeProbabilityTriggerCeiling).coerceIn(0.0, 1.0)
            survivalHealthThreshold = json.double("survivalHealthThreshold", survivalHealthThreshold).coerceIn(0.0, 6.0)
            pendingHomeMonitorMillis = json.long("pendingHomeMonitorMillis", pendingHomeMonitorMillis).coerceIn(1_000L, 60_000L)
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
            criticalHealthEmergency = criticalHealthEmergency,
            criticalHealthHearts = criticalHealthHearts,
            ultraCriticalHearts = ultraCriticalHearts,
            swarmTriggerEnabled = swarmTriggerEnabled,
            swarmMobThreshold = swarmMobThreshold,
            swarmHealthThreshold = swarmHealthThreshold,
            creeperEmergencyEnabled = creeperEmergencyEnabled,
            recentDamageWindowTicks = recentDamageWindowTicks,
            criticalEmergencyCooldownMillis = criticalEmergencyCooldownMillis,
            burstDamageEnabled = burstDamageEnabled,
            burstDamagePercentThreshold = burstDamagePercentThreshold,
            deathFailsafeEnabled = deathFailsafeEnabled,
            ultraCriticalAnyDamage = ultraCriticalAnyDamage,
            loggingVerbosity = loggingVerbosity.name.lowercase(),
            riskEngineEnabled = riskEngineEnabled,
            riskDebug = riskDebug,
            burstAbsoluteThreshold = burstAbsoluteThreshold,
            burstVelocityThreshold = burstVelocityThreshold,
            lethalBurstVelocityThreshold = lethalBurstVelocityThreshold,
            burstMediumThreshold = burstMediumThreshold,
            burstCriticalThreshold = burstCriticalThreshold,
            burstPercentWindowTicks = burstPercentWindowTicks,
            healthVelocityWeight = healthVelocityWeight,
            burstWeight = burstWeight,
            damagePredictionWeight = damagePredictionWeight,
            targetingWeight = targetingWeight,
            enchantmentWeight = enchantmentWeight,
            trajectoryWeight = trajectoryWeight,
            homeMaxSlots = homeMaxSlots,
            homeUseMaxSlot = homeUseMaxSlot,
            homeAutoDelete = homeAutoDelete,
            homeDeleteDelayMillis = homeDeleteDelayMillis,
            maceDownwardVelocityThreshold = maceDownwardVelocityThreshold,
            maceMinimumHeightAdvantage = maceMinimumHeightAdvantage,
            maceFallDamageScale = maceFallDamageScale,
            maceVelocityDamageScale = maceVelocityDamageScale,
            moddedProjectileGravity = moddedProjectileGravity,
            moddedProjectileHitboxRadius = moddedProjectileHitboxRadius,
            moddedProjectileDrag = moddedProjectileDrag,
            healthForecastTicks = healthForecastTicks,
            threatCorrelationWindowTicks = threatCorrelationWindowTicks,
            survivalSimulationTicks = survivalSimulationTicks,
            survivalSimulationBranches = survivalSimulationBranches,
            deathProbabilityThreshold = deathProbabilityThreshold,
            escapeProbabilityTriggerCeiling = escapeProbabilityTriggerCeiling,
            survivalHealthThreshold = survivalHealthThreshold,
            pendingHomeMonitorMillis = pendingHomeMonitorMillis,
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
        criticalHealthEmergency = props.bool("criticalHealthEmergency", criticalHealthEmergency)
        criticalHealthHearts = props.double("criticalHealthHearts", criticalHealthHearts)
        ultraCriticalHearts = props.double("ultraCriticalHearts", ultraCriticalHearts)
        swarmTriggerEnabled = props.bool("swarmTriggerEnabled", swarmTriggerEnabled)
        swarmMobThreshold = props.int("swarmMobThreshold", swarmMobThreshold).coerceAtLeast(1)
        swarmHealthThreshold = props.double("swarmHealthThreshold", swarmHealthThreshold)
        creeperEmergencyEnabled = props.bool("creeperEmergencyEnabled", creeperEmergencyEnabled)
        recentDamageWindowTicks = props.int("recentDamageWindowTicks", recentDamageWindowTicks).coerceIn(1, 200)
        criticalEmergencyCooldownMillis = props.long("criticalEmergencyCooldownMillis", criticalEmergencyCooldownMillis)
        burstDamageEnabled = props.bool("burstDamageEnabled", burstDamageEnabled)
        burstDamagePercentThreshold = props.double("burstDamagePercentThreshold", burstDamagePercentThreshold)
        deathFailsafeEnabled = props.bool("deathFailsafeEnabled", deathFailsafeEnabled)
        ultraCriticalAnyDamage = props.bool("ultraCriticalAnyDamage", ultraCriticalAnyDamage)

        riskEngineEnabled = props.bool("riskEngineEnabled", riskEngineEnabled)
        riskDebug = props.bool("riskDebug", riskDebug)
        burstAbsoluteThreshold = props.double("burstAbsoluteThreshold", burstAbsoluteThreshold)
        burstVelocityThreshold = props.double("burstVelocityThreshold", burstVelocityThreshold)
        healthVelocityWeight = props.double("healthVelocityWeight", healthVelocityWeight)
        burstWeight = props.double("burstWeight", burstWeight)
        damagePredictionWeight = props.double("damagePredictionWeight", damagePredictionWeight)
        targetingWeight = props.double("targetingWeight", targetingWeight)
        enchantmentWeight = props.double("enchantmentWeight", enchantmentWeight)
        trajectoryWeight = props.double("trajectoryWeight", trajectoryWeight)
        homeMaxSlots = props.int("homeMaxSlots", homeMaxSlots).coerceIn(1, 4)
        homeAutoDelete = props.bool("homeAutoDelete", homeAutoDelete)
        homeDeleteDelayMillis = props.long("homeDeleteDelayMillis", homeDeleteDelayMillis)
        healthForecastTicks = props.int("healthForecastTicks", healthForecastTicks).coerceIn(20, 200)
        threatCorrelationWindowTicks = props.int("threatCorrelationWindowTicks", threatCorrelationWindowTicks).coerceIn(1, 80)
        survivalSimulationTicks = props.int("survivalSimulationTicks", survivalSimulationTicks).coerceIn(20, 200)
        survivalSimulationBranches = props.int("survivalSimulationBranches", survivalSimulationBranches).coerceIn(64, 2048)
        deathProbabilityThreshold = props.double("deathProbabilityThreshold", deathProbabilityThreshold).coerceIn(0.50, 1.0)
        escapeProbabilityTriggerCeiling = props.double("escapeProbabilityTriggerCeiling", escapeProbabilityTriggerCeiling).coerceIn(0.0, 1.0)
        pendingHomeMonitorMillis = props.long("pendingHomeMonitorMillis", pendingHomeMonitorMillis).coerceIn(1_000L, 60_000L)
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
        val criticalHealthEmergency: Boolean,
        val criticalHealthHearts: Double,
        val ultraCriticalHearts: Double,
        val swarmTriggerEnabled: Boolean,
        val swarmMobThreshold: Int,
        val swarmHealthThreshold: Double,
        val creeperEmergencyEnabled: Boolean,
        val recentDamageWindowTicks: Int,
        val criticalEmergencyCooldownMillis: Long,
        val burstDamageEnabled: Boolean,
        val burstDamagePercentThreshold: Double,
        val deathFailsafeEnabled: Boolean,
        val ultraCriticalAnyDamage: Boolean,
        val loggingVerbosity: String,
        val riskEngineEnabled: Boolean,
        val riskDebug: Boolean,
        val burstAbsoluteThreshold: Double,
        val burstVelocityThreshold: Double,
        val lethalBurstVelocityThreshold: Double,
        val burstMediumThreshold: Double,
        val burstCriticalThreshold: Double,
        val burstPercentWindowTicks: Int,
        val healthVelocityWeight: Double,
        val burstWeight: Double,
        val damagePredictionWeight: Double,
        val targetingWeight: Double,
        val enchantmentWeight: Double,
        val trajectoryWeight: Double,
        val homeMaxSlots: Int,
        val homeUseMaxSlot: Boolean,
        val homeAutoDelete: Boolean,
        val homeDeleteDelayMillis: Long,
        val maceDownwardVelocityThreshold: Double,
        val maceMinimumHeightAdvantage: Double,
        val maceFallDamageScale: Double,
        val maceVelocityDamageScale: Double,
        val moddedProjectileGravity: Double,
        val moddedProjectileHitboxRadius: Double,
        val moddedProjectileDrag: Double,
        val healthForecastTicks: Int,
        val threatCorrelationWindowTicks: Int,
        val survivalSimulationTicks: Int,
        val survivalSimulationBranches: Int,
        val deathProbabilityThreshold: Double,
        val escapeProbabilityTriggerCeiling: Double,
        val survivalHealthThreshold: Double,
        val pendingHomeMonitorMillis: Long,
    )
}
