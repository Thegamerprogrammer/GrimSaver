package net.grimsaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreatTriggerGateTest {
    private var now = 0L
    private val gate = ThreatTriggerGate(
        clockMillis = { now },
        cooldownMillisProvider = { 10L },
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
    fun singleCycleReturnsToIdle() {
        gate.markThreatDetected(threat)
        assertEquals(EmergencyLifecycleState.THREAT_DETECTED, gate.currentState())
        gate.markTriggered(threat)
        assertEquals(EmergencyLifecycleState.EMERGENCY_TRIGGERED, gate.currentState())
        gate.onHomeCreated("1", threat)
        assertEquals(EmergencyLifecycleState.HOME_CREATED, gate.currentState())
        gate.onTeleportExecuted("1")
        assertEquals(EmergencyLifecycleState.TELEPORT_EXECUTED, gate.currentState())
        gate.onCleanupStarted("1")
        assertEquals(EmergencyLifecycleState.CLEANUP, gate.currentState())
        gate.onCleanupCompleted("1")
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
        assertNull(gate.activeHome())
    }

    @Test
    fun staleLifecycleResetsAfterFailedCleanup() {
        gate.markThreatDetected(threat)
        gate.markTriggered(threat)
        gate.onHomeCreated("1", threat)
        now = 101L
        gate.observeContext("server", "dimension", playerAlive = true)
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }

    @Test
    fun cleanupFailureResetsImmediately() {
        gate.markThreatDetected(threat)
        gate.markTriggered(threat)
        gate.onHomeCreated("1", threat)
        gate.onCleanupFailed("1", IllegalStateException("injected"))
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }

    @Test
    fun worldTransitionResetsActiveLifecycle() {
        gate.observeContext("server-a", "overworld")
        gate.markThreatDetected(threat)
        gate.markTriggered(threat)
        gate.onHomeCreated("1", threat)
        gate.observeContext("server-a", "nether")
        assertEquals(EmergencyLifecycleState.IDLE, gate.currentState())
    }

    @Test
    fun repeatedCyclesReturnToIdleEveryTime() {
        repeat(50) { index ->
            completeCycle(index.toString())
            assertEquals(EmergencyLifecycleState.IDLE, gate.currentState(), "cycle $index did not return to idle")
            assertNull(gate.activeHome(), "cycle $index leaked active home")
            now += 11L
        }
    }

    @Test
    fun stressCyclesDoNotAccumulateActiveState() {
        repeat(500) { index ->
            completeCycle((index % 4 + 1).toString())
            assertEquals(EmergencyLifecycleState.IDLE, gate.currentState(), "stress cycle $index did not return to idle")
            assertNull(gate.activeHome(), "stress cycle $index leaked active home")
            now += 11L
        }
    }

    private fun completeCycle(homeName: String) {
        gate.markThreatDetected(threat)
        gate.markTriggered(threat)
        gate.onHomeCreated(homeName, threat)
        gate.onTeleportExecuted(homeName)
        gate.onCleanupStarted(homeName)
        gate.onCleanupCompleted(homeName)
    }

}
