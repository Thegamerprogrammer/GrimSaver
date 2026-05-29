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

    override fun onInitializeClient() {
        GrimSaverConfig.load()
        val lastStandLogger = LastStandLogger()
        homeManager = HomeManager(lastStandLogger)
        chatManager = ChatManager(homeManager).also { it.register() }
        threatDetector = ThreatDetector(homeManager)

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> onClientTick(client) })
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { shutdown() })
        logger.info("GrimSaver/LastStand loaded silently with chat commands .grimsaver and .gs; no GUI, HUD, renderer, MCEF, Chromium, DJL, or PyTorch dependencies are registered.")
    }

    private fun onClientTick(client: Minecraft) {
        val player = client.player ?: return
        val level = client.level ?: return
        if (!GrimSaverConfig.enabled || !player.isAlive) return

        tickCounter++
        if (tickCounter % GrimSaverConfig.scanEveryTicks != 0) return
        if (!detectionRunning.compareAndSet(false, true)) return

        val snapshot = SnapshotFactory.capture(client, level, player)
        executor.execute {
            try {
                threatDetector.detect(snapshot)?.let { threat ->
                    client.execute {
                        homeManager.tryTrigger(client, threat)?.let { savedHome ->
                            chatManager.announceSavedHome(client, savedHome)
                        }
                    }
                }
            } catch (throwable: Throwable) {
                logger.warn("Threat scan failed", throwable)
            } finally {
                detectionRunning.set(false)
            }
        }
    }

    private fun shutdown() {
        executor.shutdownNow()
        executor.awaitTermination(250, TimeUnit.MILLISECONDS)
    }
}
