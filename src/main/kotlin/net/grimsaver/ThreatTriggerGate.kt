package net.grimsaver

import net.minecraft.client.Minecraft

class ThreatTriggerGate(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMillisProvider: () -> Long = { maxOf(GrimSaverConfig.minCommandIntervalMillis, GrimSaverConfig.globalCooldownMillis) },
    private val staleLifecycleMillisProvider: () -> Long = { maxOf(GrimSaverConfig.threatResetTimeoutMillis, GrimSaverConfig.homeDeleteDelayMillis + 5_000L) }
) : EmergencyLifecycleListener {
    @Volatile private var state = EmergencyLifecycleState.IDLE
    @Volatile private var lastTriggerMillis = 0L
    @Volatile private var lastTransitionMillis = 0L
    @Volatile private var lastServerKey: String? = null
    @Volatile private var lastDimension: String? = null
    @Volatile private var activeThreatKey: String? = null
    @Volatile private var activeHomeName: String? = null

    fun observe(client: Minecraft) {
        try {
            val level = client.level ?: return reset("no-level")
            observeContext(
                serverKey = client.currentServer?.ip ?: "singleplayer",
                dimension = level.dimension().toString(),
                playerAlive = runCatching { client.player?.isAlive != false }.getOrDefault(true)
            )
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("lifecycle-observe", "GrimSaver lifecycle observation failed; force-resetting gate to keep the mod recoverable", throwable)
            reset("observe-failure")
        }
    }

    @Synchronized
    fun observeContext(serverKey: String?, dimension: String?, playerAlive: Boolean = true, now: Long = clockMillis()) {
        val normalizedServer = serverKey ?: "singleplayer"
        val normalizedDimension = dimension ?: "unknown"
        if (lastServerKey == null || lastDimension == null) {
            lastServerKey = normalizedServer
            lastDimension = normalizedDimension
        } else if (normalizedServer != lastServerKey || normalizedDimension != lastDimension) {
            lastServerKey = normalizedServer
            lastDimension = normalizedDimension
            reset("world-change:$normalizedServer/$normalizedDimension")
            return
        }
        if (!playerAlive && state != EmergencyLifecycleState.IDLE) {
            debugGrimSaver("GrimSaver lifecycle observed player death while in {}; cleanup will be allowed to finish or stale-reset", state)
        }
        if (state == EmergencyLifecycleState.IDLE) return
        if (state == EmergencyLifecycleState.HOME_CREATED || state == EmergencyLifecycleState.TELEPORT_EXECUTED || state == EmergencyLifecycleState.CLEANUP) {
            if (now - lastTransitionMillis >= staleLifecycleMillisProvider()) {
                reset("stale-lifecycle:${state.name.lowercase()}")
                return
            }
        }
        if (state == EmergencyLifecycleState.EMERGENCY_TRIGGERED && now - lastTriggerMillis >= cooldownMillisProvider()) {
            reset("cooldown-expired")
        }
    }

    @Synchronized
    fun canTrigger(threat: Threat, now: Long = clockMillis()): Boolean {
        expireCooldown(now)
        if (state != EmergencyLifecycleState.IDLE) {
            debugGrimSaver("GrimSaver trigger blocked in lifecycle state {} for threat {}", state, threat.cooldownKey)
            return false
        }
        return true
    }

    @Synchronized
    fun markThreatDetected(threat: Threat) {
        if (state != EmergencyLifecycleState.IDLE) return
        activeThreatKey = threat.cooldownKey
        transition(EmergencyLifecycleState.THREAT_DETECTED, "threat=${threat.cooldownKey}")
    }

    @Synchronized
    fun markTriggered(threat: Threat) {
        activeThreatKey = threat.cooldownKey
        lastTriggerMillis = clockMillis()
        transition(
            EmergencyLifecycleState.EMERGENCY_TRIGGERED,
            "threat=${threat.kind.id} damage=${threat.damage} predicted=${threat.predictedDamage} remaining=${threat.health - threat.predictedDamage} confidence=${threat.confidence} source=${threat.source} cooldownMs=${cooldownMillisProvider()}"
        )
    }

    override fun onHomeCreated(homeName: String, threat: Threat) = synchronized(this) {
        activeHomeName = homeName
        activeThreatKey = threat.cooldownKey
        transition(EmergencyLifecycleState.HOME_CREATED, "home=$homeName threat=${threat.cooldownKey}")
    }

    override fun onTeleportExecuted(homeName: String) = synchronized(this) {
        activeHomeName = homeName
        transition(EmergencyLifecycleState.TELEPORT_EXECUTED, "home=$homeName")
    }

    override fun onCleanupStarted(homeName: String) = synchronized(this) {
        activeHomeName = homeName
        transition(EmergencyLifecycleState.CLEANUP, "home=$homeName")
    }

    override fun onCleanupCompleted(homeName: String) = synchronized(this) {
        if (activeHomeName == null || activeHomeName == homeName) reset("cleanup-completed:$homeName") else debugGrimSaver("Ignoring cleanup completion for non-active home {} while active home is {}", homeName, activeHomeName)
    }

    override fun onCleanupFailed(homeName: String, throwable: Throwable) = synchronized(this) {
        warnGrimSaverFailure("cleanup-failed-$homeName", "GrimSaver cleanup failed for home $homeName; resetting lifecycle so future emergency cycles can continue", throwable)
        reset("cleanup-failed:$homeName")
    }

    @Synchronized
    fun forceReset(reason: String) = reset(reason)

    @Synchronized
    fun currentState(): EmergencyLifecycleState = state

    @Synchronized
    fun activeHome(): String? = activeHomeName

    private fun expireCooldown(now: Long) {
        if (state == EmergencyLifecycleState.EMERGENCY_TRIGGERED && now - lastTriggerMillis >= cooldownMillisProvider()) {
            reset("cooldown-expired")
        }
    }

    private fun transition(next: EmergencyLifecycleState, detail: String) {
        val previous = state
        state = next
        lastTransitionMillis = clockMillis()
        debugGrimSaver("GrimSaver lifecycle {} -> {} ({})", previous, next, detail)
    }

    private fun reset(reason: String) {
        val previous = state
        state = EmergencyLifecycleState.IDLE
        activeThreatKey = null
        activeHomeName = null
        lastTransitionMillis = clockMillis()
        if (previous != EmergencyLifecycleState.IDLE) debugGrimSaver("GrimSaver lifecycle {} -> IDLE ({})", previous, reason)
    }
}
