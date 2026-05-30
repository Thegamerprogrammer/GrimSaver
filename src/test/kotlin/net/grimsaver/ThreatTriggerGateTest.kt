package net.grimsaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreatTriggerGateTest {
    private var now = 0L
    private val gate = ThreatTriggerGate(
        clockMillis = { now },
        staleLifecycleMillisProvider = { 100L }
    )
    private val threat = Threat(
        kind = ThreatKind.LETHAL_PROJECTILE,
        damage = 20.0,
        health = 10.0,
        source = "test",
        reason = "test threat",
        confidence = 1.0,
        position = net.minecraft.world.phys.Vec3.ZERO
    )

    @Test
    fun sethomeCycleReturnsToIdleWithoutWaitingForHomeCommand() {
        gate.markThreatDetected(threat)
        assertEquals(EmergencyLifecycleState.THREAT_DETECTED, gate.currentState())
        gate.markTriggered(threat)
        assertEquals(EmergencyLifecycleState.EMERGENCY_TRIGGERED, gate.currentState())
        gate.onHomeCreated("1", threat)
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
        assertNull(gate.activeHome())
        assertTrue(gate.canTrigger(threat), "created homes must not block future detections while waiting for /home")
    }

    @Test
    fun cleanupAfterPlayerUsesHomeReturnsToIdle() {
        gate.onTeleportExecuted("1")
        assertEquals(EmergencyLifecycleState.TELEPORT_EXECUTED, gate.currentState())
        gate.onCleanupStarted("1")
        assertEquals(EmergencyLifecycleState.CLEANUP, gate.currentState())
        gate.onCleanupCompleted("1")
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
        assertNull(gate.activeHome())
    }

    @Test
    fun staleCleanupResetsAfterFailedCleanup() {
        gate.onTeleportExecuted("1")
        gate.onCleanupStarted("1")
        now = 101L
        gate.observeContext("server", "dimension", playerAlive = true)
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }

    @Test
    fun cleanupFailureResetsImmediately() {
        gate.onTeleportExecuted("1")
        gate.onCleanupStarted("1")
        gate.onCleanupFailed("1", IllegalStateException("injected"))
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }

    @Test
    fun worldTransitionResetsActiveCleanupLifecycle() {
        gate.observeContext("server-a", "overworld")
        gate.onTeleportExecuted("1")
        gate.onCleanupStarted("1")
        gate.observeContext("server-a", "nether")
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }


    @Test
    fun cleanupCompletionDoesNotClobberNewInFlightThreat() {
        gate.onTeleportExecuted("1")
        gate.onCleanupStarted("1")
        gate.markThreatDetected(threat)
        assertEquals(EmergencyLifecycleState.THREAT_DETECTED, gate.currentState())
        gate.onCleanupCompleted("1")
        assertEquals(EmergencyLifecycleState.THREAT_DETECTED, gate.currentState())
    }

    @Test
    fun repeatedSethomeCyclesNeverWaitForManualHome() {
        repeat(50) { index ->
            completeSethomeCycle(index.toString())
            assertEquals(EmergencyLifecycleState.IDLE, gate.currentState(), "cycle $index did not return to idle")
            assertNull(gate.activeHome(), "cycle $index leaked active home")
            assertTrue(gate.canTrigger(threat), "cycle $index blocked future detection")
            now += 1L
        }
    }

    @Test
    fun stressSethomeCyclesDoNotAccumulateActiveState() {
        repeat(500) { index ->
            completeSethomeCycle((index % 4 + 1).toString())
            assertEquals(EmergencyLifecycleState.IDLE, gate.currentState(), "stress cycle $index did not return to idle")
            assertNull(gate.activeHome(), "stress cycle $index leaked active home")
            assertTrue(gate.canTrigger(threat), "stress cycle $index blocked future detection")
            now += 1L
        }
    }

    private fun completeSethomeCycle(homeName: String) {
        gate.markThreatDetected(threat)
        gate.markTriggered(threat)
        gate.onHomeCreated(homeName, threat)
    }
}
