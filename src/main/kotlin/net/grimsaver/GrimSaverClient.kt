package net.grimsaver

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object GrimSaverClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("GrimSaver")
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
        homeManager = HomeManager(lastStandLogger)
        chatManager = ChatManager(homeManager).also { it.register() }
        threatDetector = ThreatDetector(homeManager)
        riskEngine = RiskEngine(GrimSaverConfig)

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> onClientTick(client) })
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { shutdown() })
        logger.info("GrimSaver/LastStand loaded silently with chat commands .grimsaver and .gs; no GUI, HUD, renderer, MCEF, Chromium, DJL, or PyTorch dependencies are registered.")
    }

    private fun onClientTick(client: Minecraft) {
        val player = client.player ?: return
        val level = client.level ?: return
        triggerGate.observe(client)
        if (!GrimSaverConfig.enabled) return

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
        emergencyDetector.detect(client, snapshot)?.let { emergency ->
            triggerThreat(client, emergency)
            if (!player.isAlive) return
        }

        if (!player.isAlive) return

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
                    if (assessment.shouldTriggerSetHome && assessment.confidence >= GrimSaverConfig.lethalConfidenceThreshold && assessment.healthVelocity <= -GrimSaverConfig.burstVelocityThreshold) {
                        client.execute { triggerThreat(client, assessment.toThreat(snapshot)) }
                    }
                } else {
                    threatDetector.detect(snapshot)?.let { threat ->
                        client.execute { triggerThreat(client, threat) }
                    }
                }
            } catch (throwable: Throwable) {
                logger.warn("Threat scan failed", throwable)
            } finally {
                detectionRunning.set(false)
            }
        }
    }

    private fun triggerThreat(client: Minecraft, threat: Threat) {
        try {
            if (!triggerGate.canTrigger(threat)) return
            homeManager.tryTrigger(client, threat)?.let { savedHome ->
                triggerGate.markTriggered(threat)
                chatManager.announceSavedHome(client, savedHome)
            }
        } catch (throwable: Throwable) {
            warnGrimSaverFailure("threat-trigger", "GrimSaver failed while handling a detected threat; continuing safely", throwable)
        }
    }

    private fun shutdown() {
        homeManager.shutdown()
        executor.shutdownNow()
        executor.awaitTermination(250, TimeUnit.MILLISECONDS)
    }
}
