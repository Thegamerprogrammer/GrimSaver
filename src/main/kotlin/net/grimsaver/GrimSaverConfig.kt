package net.grimsaver

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object GrimSaverConfig {
    var enabled = true
    var projectileThreats = true
    var meleeThreats = true
    var fallThreats = true
    var combineThreats = true
    var safetyMargin = 2.0
    var scanEveryTicks = 2
    var globalCooldownMillis = 4_000L
    var perThreatCooldownMillis = 1_500L
    var projectileLookaheadTicks = 80
    var meleeRangePadding = 0.75
    var fallDamageMargin = 0.5

    private val path = FabricLoader.getInstance().configDir.resolve("LastStand").resolve("grimsaver.properties")

    fun load() {
        if (!path.parent.exists()) path.parent.createDirectories()
        if (!path.exists()) {
            saveDefaults()
            return
        }

        val props = Properties()
        Files.newInputStream(path).use(props::load)
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

    private fun saveDefaults() {
        val props = Properties().apply {
            setProperty("enabled", enabled.toString())
            setProperty("projectileThreats", projectileThreats.toString())
            setProperty("meleeThreats", meleeThreats.toString())
            setProperty("fallThreats", fallThreats.toString())
            setProperty("combineThreats", combineThreats.toString())
            setProperty("safetyMargin", safetyMargin.toString())
            setProperty("scanEveryTicks", scanEveryTicks.toString())
            setProperty("globalCooldownMillis", globalCooldownMillis.toString())
            setProperty("perThreatCooldownMillis", perThreatCooldownMillis.toString())
            setProperty("projectileLookaheadTicks", projectileLookaheadTicks.toString())
            setProperty("meleeRangePadding", meleeRangePadding.toString())
            setProperty("fallDamageMargin", fallDamageMargin.toString())
        }
        Files.newOutputStream(path).use { props.store(it, "GrimSaver silent emergency home saver") }
    }

    private fun Properties.bool(key: String, default: Boolean) = getProperty(key)?.toBooleanStrictOrNull() ?: default
    private fun Properties.double(key: String, default: Double) = getProperty(key)?.toDoubleOrNull() ?: default
    private fun Properties.int(key: String, default: Int) = getProperty(key)?.toIntOrNull() ?: default
    private fun Properties.long(key: String, default: Long) = getProperty(key)?.toLongOrNull() ?: default
}
