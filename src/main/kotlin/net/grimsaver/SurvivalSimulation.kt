package net.grimsaver

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Forecast returned by the real-time survival simulator. It answers "does this
 * player probably die in the next few seconds?" instead of "can known damage add
 * up above current health?".
 */
data class SurvivalForecast(
    val deathProbability: Double,
    val survivalProbability: Double,
    val escapeProbability: Double,
    val expectedRemainingHealth: Double,
    val minimumPredictedHealth: Double,
    val lethalTick: Int?,
    val confidence: Double
)

data class EscapeAssessment(
    val escapeProbability: Double,
    val estimatedEscapeTimeTicks: Int?,
    val safePathExists: Boolean,
    val lineOfSightBreakProbability: Double,
    val blockPlacementSuccessProbability: Double
)

data class ThreatClassifierResult(
    val threatSeverity: Double,
    val deathProbabilityHint: Double
)

class EscapeProbabilityEngine(private val config: GrimSaverConfig = GrimSaverConfig) {
    fun assess(snapshot: WorldSnapshot, window: CombatWindow): EscapeAssessment {
        val terrain = snapshot.terrain
        val player = snapshot.player
        val nearbyThreats = snapshot.livingEntities.count { it.isEnemy && it.position.distanceTo(player.position) <= 10.0 }
        val pursuingThreats = snapshot.livingEntities.count { entity ->
            entity.isEnemy && (entity.targetId == snapshot.playerId || entity.velocity.dot(player.position.subtract(entity.position)) > 0.02)
        }
        val projectilePressure = window.sources.count { it.kind == DamageSourceKind.PROJECTILE && it.tick <= 60 }
        val explosivePressure = window.sources.count { it.specialEffects.any { effect -> effect.contains("explosion", true) } }

        val routeScore = (terrain.openEscapeDirections / 8.0).coerceIn(0.0, 1.0)
        val losScore = (terrain.lineOfSightBreaksNearby / 8.0).coerceIn(0.0, 1.0)
        val waterScore = if (terrain.waterNearby) 0.10 else 0.0
        val corridorScore = if (terrain.doorwayOrCorridorNearby) 0.08 else 0.0
        val blockScore = blockPlacementProbability(snapshot, window)
        val pursuitPenalty = (nearbyThreats * 0.08 + pursuingThreats * 0.10 + projectilePressure * 0.06 + explosivePressure * 0.16 + terrain.hazardDensity * 0.35).coerceIn(0.0, 0.85)
        val probability = (0.12 + routeScore * 0.42 + losScore * 0.18 + waterScore + corridorScore + blockScore * 0.22 - pursuitPenalty).coerceIn(0.0, 0.98)
        val safePath = probability >= 0.55 && routeScore > 0.0 && terrain.hazardDensity < 0.20
        val estimated = if (safePath) (18 + nearbyThreats * 6 - terrain.openEscapeDirections * 2).coerceIn(10, 100) else null
        return EscapeAssessment(probability, estimated, safePath, (losScore + blockScore * 0.5).coerceIn(0.0, 1.0), blockScore)
    }

    private fun blockPlacementProbability(snapshot: WorldSnapshot, window: CombatWindow): Double {
        if (!snapshot.player.inventory.canPlaceSurvivalBlocks) return 0.0
        val blocks = snapshot.player.inventory.placeableBlockCount
        val routeContext = when {
            snapshot.terrain.doorwayOrCorridorNearby -> 0.55
            snapshot.terrain.blockedDirections >= 5 -> 0.42
            snapshot.terrain.lineOfSightBreaksNearby >= 4 -> 0.28
            else -> 0.14
        }
        val timePressurePenalty = window.sources.count { it.tick <= 15 } * 0.10
        val explosivePenalty = if (window.sources.any { it.specialEffects.any { effect -> effect.contains("explosion", true) } }) 0.18 else 0.0
        return (routeContext + min(0.18, blocks / 128.0) - timePressurePenalty - explosivePenalty).coerceIn(0.0, 0.75)
    }
}

class MiniThreatClassifier {
    fun classify(snapshot: WorldSnapshot, window: CombatWindow, escape: EscapeAssessment): ThreatClassifierResult {
        val player = snapshot.player
        val healthPressure = (1.0 - player.effectiveHealth / max(1.0, player.maxHealth + player.absorption)).coerceIn(0.0, 1.0)
        val density = (snapshot.livingEntities.count { it.isEnemy && it.position.distanceTo(player.position) <= 8.0 } / 8.0).coerceIn(0.0, 1.0)
        val projectile = (snapshot.projectiles.size / 6.0).coerceIn(0.0, 1.0)
        val explosive = if (window.sources.any { it.specialEffects.any { effect -> effect.contains("explosion", true) } }) 1.0 else 0.0
        val armorWeakness = (1.0 - (player.armor / 20.0).coerceIn(0.0, 1.0))
        val escapeBlock = 1.0 - escape.escapeProbability
        val severity = (healthPressure * 0.28 + density * 0.18 + projectile * 0.12 + explosive * 0.18 + armorWeakness * 0.10 + escapeBlock * 0.14).coerceIn(0.0, 1.0)
        return ThreatClassifierResult(severity, sigmoid((severity - 0.72) * 7.5))
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

class SimulationEngine(
    private val config: GrimSaverConfig = GrimSaverConfig,
    private val escapeEngine: EscapeProbabilityEngine = EscapeProbabilityEngine(config),
    private val classifier: MiniThreatClassifier = MiniThreatClassifier()
) {
    fun simulate(snapshot: WorldSnapshot, window: CombatWindow): SurvivalForecast {
        val horizon = config.survivalSimulationTicks.coerceIn(20, 200)
        val branchCount = config.survivalSimulationBranches.coerceIn(64, 2048)
        val escape = escapeEngine.assess(snapshot, window)
        val ml = classifier.classify(snapshot, window, escape)
        val player = snapshot.player
        val threshold = config.survivalHealthThreshold
        var deathWeight = 0.0
        var totalWeight = 0.0
        var expectedHealth = 0.0
        var minHealth = player.effectiveHealth
        var firstLethal: Int? = null
        val totemBranches = if (player.inventory.hasTotem) branchCount / 5 else 0

        for (branch in 0 until branchCount) {
            val aggressiveness = 0.72 + (branch % 9) * 0.055
            val dodgeRoll = ((branch * 37) % 100) / 100.0
            val escapeSucceeds = dodgeRoll < escape.escapeProbability
            val branchWeight = if (escapeSucceeds) escape.escapeProbability / max(1, (branchCount * escape.escapeProbability).toInt()).coerceAtLeast(1) else (1.0 - escape.escapeProbability) / max(1, (branchCount * (1.0 - escape.escapeProbability)).toInt()).coerceAtLeast(1)
            var health = player.effectiveHealth
            var usedTotem = false
            var lethalTick: Int? = null
            for (tick in 1..horizon) {
                health += player.regenerationPerSecond / 20.0
                if (escapeSucceeds && escape.estimatedEscapeTimeTicks != null && tick >= escape.estimatedEscapeTimeTicks) {
                    health += 0.015
                    continue
                }
                window.sources.filter { it.tick == tick || (it.kind == DamageSourceKind.ENTITY && tick > it.tick && (tick - it.tick) % 20 == 0) }.forEach { source ->
                    val impactProbability = adjustedImpactProbability(source, escape, tick, horizon) * aggressiveness
                    if (((branch * 17 + tick * 13 + source.source.hashCode()).floorMod(100)) / 100.0 < impactProbability.coerceIn(0.0, 0.98)) {
                        health -= source.damage
                    }
                }
                if (health <= threshold && player.inventory.hasTotem && !usedTotem && branch < totemBranches) {
                    usedTotem = true
                    health = 11.0
                }
                if (health <= threshold && lethalTick == null) lethalTick = tick
                minHealth = min(minHealth, health)
            }
            val branchDied = lethalTick != null
            if (branchDied) deathWeight += branchWeight
            totalWeight += branchWeight
            expectedHealth += health * branchWeight
            if (branchDied && (firstLethal == null || lethalTick!! < firstLethal!!)) firstLethal = lethalTick
        }

        val simulatedDeath = if (totalWeight <= 0.0) 0.0 else (deathWeight / totalWeight).coerceIn(0.0, 1.0)
        val deathProbability = (simulatedDeath * 0.82 + ml.deathProbabilityHint * 0.18).coerceIn(0.0, 1.0)
        val confidence = (window.sources.maxOfOrNull { it.confidence } ?: 0.0) * 0.55 + ml.threatSeverity * 0.25 + (1.0 - escape.escapeProbability) * 0.20
        return SurvivalForecast(
            deathProbability = deathProbability,
            survivalProbability = 1.0 - deathProbability,
            escapeProbability = escape.escapeProbability,
            expectedRemainingHealth = if (totalWeight <= 0.0) player.effectiveHealth else expectedHealth / totalWeight,
            minimumPredictedHealth = minHealth,
            lethalTick = firstLethal,
            confidence = confidence.coerceIn(0.0, 1.0)
        )
    }

    private fun adjustedImpactProbability(source: TimedDamage, escape: EscapeAssessment, tick: Int, horizon: Int): Double {
        val escapeMitigation = when (source.kind) {
            DamageSourceKind.PROJECTILE -> escape.lineOfSightBreakProbability * 0.55 + escape.escapeProbability * 0.25
            DamageSourceKind.ENTITY -> escape.escapeProbability * 0.45 + escape.blockPlacementSuccessProbability * 0.25
            DamageSourceKind.ENVIRONMENT, DamageSourceKind.FALL -> 0.0
        }
        val urgency = 1.0 - (tick.toDouble() / horizon.toDouble()) * 0.18
        return (source.confidence * urgency - escapeMitigation).coerceIn(0.03, 0.98)
    }

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
}
