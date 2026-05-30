package net.grimsaver

import net.minecraft.client.Minecraft

class ThreatTriggerGate {
    private var state = State.READY
    private var lastTriggerMillis = 0L
    private var lastServerKey: String? = null
    private var lastDimension: String? = null

    fun observe(client: Minecraft) {
        val level = client.level ?: return reset("no-level")
        val serverKey = client.currentServer?.ip ?: "singleplayer"
        val dimension = level.dimension().toString()
        if (serverKey != lastServerKey || dimension != lastDimension) {
            lastServerKey = serverKey
            lastDimension = dimension
            reset("world-change")
            return
        }
        if (state == State.COOLDOWN && System.currentTimeMillis() - lastTriggerMillis >= cooldownMillis()) {
            reset("cooldown-expired")
        }
    }

    fun canTrigger(threat: Threat): Boolean {
        observe(Minecraft.getInstance())
        if (state == State.COOLDOWN) return false
        return true
    }

    fun markTriggered(threat: Threat) {
        state = State.COOLDOWN
        lastTriggerMillis = System.currentTimeMillis()
        debugGrimSaver(
            "GrimSaver gate -> COOLDOWN for {}ms after {} damage={} predicted={} remaining-health={} confidence={} source={}",
            cooldownMillis(),
            threat.kind.id,
            threat.damage,
            threat.predictedDamage,
            threat.health - threat.predictedDamage,
            threat.confidence,
            threat.source
        )
    }

    private fun reset(reason: String) {
        if (state != State.READY) debugGrimSaver("GrimSaver gate -> READY ({})", reason)
        state = State.READY
    }

    private fun cooldownMillis(): Long = maxOf(GrimSaverConfig.minCommandIntervalMillis, GrimSaverConfig.globalCooldownMillis)

    private enum class State {
        READY,
        COOLDOWN
    }
}
