package net.grimsaver

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GrimSaverClient : ClientModInitializer {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GrimSaver-ThreatWorker").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val detectionRunning = AtomicBoolean(false)
    private var tickCounter = 0

    internal lateinit var homeManager: HomeManager
    private lateinit var chatManager: ChatManager
    private lateinit var threatDetector: ThreatDetector
    private lateinit var riskEngine: RiskEngine
    private val emergencyDetector = EmergencyDetector()
    private val triggerGate = ThreatTriggerGate()

    override fun onInitializeClient() {
        GrimSaverConfig.load()
        val lastStandLogger = LastStandLogger()
        homeManager = HomeManager(lastStandLogger, triggerGate)
        chatManager = ChatManager(homeManager).also { it.register() }
        threatDetector = ThreatDetector(homeManager)
        riskEngine = RiskEngine(GrimSaverConfig)

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> onClientTick(client) })
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { shutdown() })
        infoGrimSaver("GrimSaver/LastStand loaded silently with chat commands .grimsaver and .gs; no GUI, HUD, renderer, MCEF, Chromium, DJL, or PyTorch dependencies are registered.")
    }

    private fun onClientTick(client: Minecraft) {
        runCatching { triggerGate.observe(client) }.onFailure { throwable ->
            warnGrimSaverFailure("tick-lifecycle", "GrimSaver lifecycle tick failed; resetting runtime state so scanning can continue", throwable)
            triggerGate.forceReset("tick-lifecycle-failure")
            homeManager.resetRuntimeState("tick-lifecycle-failure")
            emergencyDetector.resetRuntimeState("tick-lifecycle-failure")
            detectionRunning.set(false)
        }
        if (!GrimSaverConfig.enabled) return
        val player = client.player ?: return
        val level = client.level ?: return

        tickCounter++
        if (tickCounter % GrimSaverConfig.scanEveryTicks != 0) return
        if (!detectionRunning.compareAndSet(false, true)) return

        val snapshot = try {
            SnapshotFactory.capture(client, level, player)
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("snapshot-capture", "GrimSaver snapshot capture failed; skipping this tick", throwable)
            detectionRunning.set(false)
            return
        }
        val emergency = try {
            emergencyDetector.detect(client, snapshot)
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("emergency-detect", "GrimSaver emergency detector failed; re-enabling future scans", throwable)
            detectionRunning.set(false)
            return
        }
        emergency?.let { threat ->
            triggerThreat(client, threat)
            if (!player.isAlive) {
                detectionRunning.set(false)
                return
            }
        }

        if (!player.isAlive) {
            detectionRunning.set(false)
            return
        }

        try {
            executor.execute {
                try {
                    if (GrimSaverConfig.riskEngineEnabled) {
                        val assessment = riskEngine.assess(snapshot)
                        if (GrimSaverConfig.riskDebug) {
                            debugGrimSaver(
                                "RiskEngine assessment risk={} confidence={} burst={} predictedDamage={} velocity={} source={} trigger={}",
                                assessment.totalRiskScore,
                                assessment.confidence,
                                assessment.burstLevel,
                                assessment.predictedDamage,
                                assessment.healthVelocity,
                                assessment.primaryThreatSource,
                                assessment.shouldTriggerSetHome
                            )
                        }
                        if (assessment.shouldTriggerSetHome && assessment.confidence >= GrimSaverConfig.lethalConfidenceThreshold) {
                            client.execute { triggerThreat(client, assessment.toThreat(snapshot)) }
                        }
                    } else {
                        threatDetector.detect(snapshot)?.let { threat ->
                            client.execute { triggerThreat(client, threat) }
                        }
                    }
                } catch (throwable: Throwable) {
                    warnGrimSaverFailure("threat-scan", "Threat scan failed; worker recovered and future scans remain enabled", throwable)
                } finally {
                    detectionRunning.set(false)
                }
            }
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("threat-submit", "Unable to submit GrimSaver threat scan; re-enabling future scans", throwable)
            detectionRunning.set(false)
        }
    }

    private fun triggerThreat(client: Minecraft, threat: Threat) {
        try {
            if (!triggerGate.canTrigger(threat)) return
            triggerGate.markThreatDetected(threat)
            triggerGate.markTriggered(threat)
            val savedHome = homeManager.tryTrigger(client, threat)
            if (savedHome != null) {
                chatManager.announceSavedHome(client, savedHome)
            } else {
                triggerGate.forceReset("home-manager-declined")
            }
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("threat-trigger", "GrimSaver failed while handling a detected threat; resetting cycle state and continuing safely", throwable)
            triggerGate.forceReset("threat-trigger-failure")
            homeManager.resetRuntimeState("threat-trigger-failure")
            emergencyDetector.resetRuntimeState("threat-trigger-failure")
        }
    }

    private fun shutdown() {
        homeManager.shutdown()
        executor.shutdownNow()
        executor.awaitTermination(250, TimeUnit.MILLISECONDS)
    }
}
