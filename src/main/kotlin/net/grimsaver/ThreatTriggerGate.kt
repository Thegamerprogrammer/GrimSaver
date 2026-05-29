package net.grimsaver

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.ai.attributes.Attributes

class ThreatTriggerGate {
    private var state = State.IDLE
    private var lastTriggerMillis = 0L
    private var lastThreatKey: String? = null
    private var lastServerKey: String? = null
    private var lastDimension: String? = null

    fun observe(client: Minecraft) {
        val player = client.player ?: return reset("no-player")
        val level = client.level ?: return reset("no-level")
        val serverKey = client.currentServer?.ip ?: "singleplayer"
        val dimension = level.dimension().toString()
        if (serverKey != lastServerKey || dimension != lastDimension) {
            lastServerKey = serverKey
            lastDimension = dimension
            reset("world-change")
            return
        }
        if (!player.isAlive) {
            reset("player-dead")
            return
        }
        if (state != State.WAITING_FOR_RESET) return

        val now = System.currentTimeMillis()
        val effectiveHealth = player.health.toDouble() + player.absorptionAmount.toDouble()
        val maxHealth = player.safeAttribute(Attributes.MAX_HEALTH, 20.0)
        val recovered = effectiveHealth >= maxHealth * 0.85
        val expired = now - lastTriggerMillis >= GrimSaverConfig.threatResetTimeoutMillis
        if (recovered || expired) reset(if (recovered) "health-recovered" else "timeout")
    }

    fun canTrigger(threat: Threat): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMillis < GrimSaverConfig.minCommandIntervalMillis) return false
        if (state == State.WAITING_FOR_RESET) return false
        if (state == State.SAVEHOME_TRIGGERED && lastThreatKey == threat.cooldownKey) return false
        state = State.THREAT_DETECTED
        return true
    }

    fun markTriggered(threat: Threat) {
        state = State.WAITING_FOR_RESET
        lastTriggerMillis = System.currentTimeMillis()
        lastThreatKey = threat.cooldownKey
        debugGrimSaver(
            "GrimSaver state -> WAITING_FOR_RESET after {} damage={} health={} confidence={} source={}",
            threat.kind.id,
            threat.damage,
            threat.health,
            threat.confidence,
            threat.source
        )
    }

    private fun reset(reason: String) {
        if (state != State.IDLE) debugGrimSaver("GrimSaver state -> IDLE ({})", reason)
        state = State.IDLE
        lastThreatKey = null
    }

    private enum class State {
        IDLE,
        THREAT_DETECTED,
        SAVEHOME_TRIGGERED,
        WAITING_FOR_RESET
    }
}
