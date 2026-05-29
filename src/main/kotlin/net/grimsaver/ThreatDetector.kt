package net.grimsaver

import kotlin.math.max
import kotlin.math.min

class ThreatDetector(private val homeManager: HomeManager) {
    fun detect(snapshot: WorldSnapshot): Threat? {
        val threats = mutableListOf<Threat>()
        if (GrimSaverConfig.projectileThreats) threats += projectileThreats(snapshot)
        if (GrimSaverConfig.pvpThreats || GrimSaverConfig.mobThreats) threats += meleeThreats(snapshot)
        if (GrimSaverConfig.fallThreats) fallThreat(snapshot)?.let(threats::add)

        val requiredDamage = lethalDamageThreshold(snapshot.player.effectiveHealth)
        val reliable = threats
            .filter { it.damage >= requiredDamage && it.confidence >= GrimSaverConfig.lethalConfidenceThreshold }
            .sortedWith(compareByDescending<Threat> { it.confidence }.thenByDescending { it.damage })
        reliable.firstOrNull { homeManager.cooldownReady(it) }?.let { return it }

        if (GrimSaverConfig.combineThreats) {
            combinedThreat(snapshot, threats, requiredDamage)?.takeIf(homeManager::cooldownReady)?.let { return it }
        }
        return null
    }

    private fun projectileThreats(snapshot: WorldSnapshot): List<Threat> = snapshot.projectiles.mapNotNull { projectile ->
        val impact = TrajectoryHelper.firstPlayerIntersection(
            projectile,
            snapshot.player.boundingBox,
            GrimSaverConfig.projectileLookaheadTicks
        ) ?: return@mapNotNull null
        val prediction = DamagePredictor.projectile(projectile, snapshot.player)
        val confidence = projectileConfidence(projectile, impact.ticks, prediction.damage, snapshot.player.effectiveHealth)
        Threat(
            kind = projectile.kind(),
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = prediction.source,
            reason = prediction.reasonWithConfidence(confidence),
            confidence = confidence,
            ticksUntilImpact = impact.ticks,
            position = snapshot.player.position
        )
    }.sortedByDescending { it.damage }

    private fun meleeThreats(snapshot: WorldSnapshot): List<Threat> = snapshot.livingEntities.mapNotNull { attacker ->
        if (attacker.isPlayer && !GrimSaverConfig.pvpThreats) return@mapNotNull null
        if (!attacker.isPlayer && !GrimSaverConfig.mobThreats) return@mapNotNull null
        val reach = attacker.attackRange + GrimSaverConfig.meleeRangePadding
        val distance = attacker.position.distanceTo(snapshot.player.position)
        val inRange = distance <= reach || attacker.boundingBox.inflate(reach).intersects(snapshot.player.boundingBox)
        val targeting = attacker.targetId == snapshot.playerId
        if (!inRange && !targeting) return@mapNotNull null
        val prediction = DamagePredictor.melee(attacker, snapshot.player)
        val confidence = meleeConfidence(attacker, inRange, targeting, distance, reach, prediction.damage, snapshot.player.effectiveHealth)
        Threat(
            kind = if (attacker.isPlayer) ThreatKind.MELEE_PLAYER else ThreatKind.MELEE_MOB,
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = prediction.source,
            reason = prediction.reasonWithConfidence(confidence),
            confidence = confidence,
            position = snapshot.player.position
        )
    }.sortedByDescending { it.damage }

    private fun fallThreat(snapshot: WorldSnapshot): Threat? {
        val prediction = DamagePredictor.fall(snapshot.player)
        if (prediction.damage <= 0.0) return null
        val confidence = fallConfidence(snapshot.player, prediction.damage)
        return Threat(
            kind = ThreatKind.FALL_DAMAGE,
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = snapshot.dimension,
            reason = prediction.reasonWithConfidence(confidence),
            confidence = confidence,
            ticksUntilImpact = max(1, (-snapshot.player.velocity.y * 2.0).toInt()),
            position = snapshot.player.position
        )
    }

    private fun combinedThreat(snapshot: WorldSnapshot, threats: List<Threat>, requiredDamage: Double): Threat? {
        val reliablePieces = threats.filter { it.confidence >= 0.90 && it.damage > 0.0 }
        if (reliablePieces.size < 2) return null
        val combined = reliablePieces.sumOf { it.damage }
        if (combined < requiredDamage * 1.15) return null
        val confidence = (reliablePieces.minOf { it.confidence } - 0.04).coerceIn(0.0, 1.0)
        if (confidence < GrimSaverConfig.lethalConfidenceThreshold) return null
        return Threat(
            kind = ThreatKind.COMBINED,
            damage = combined,
            health = snapshot.player.effectiveHealth,
            source = reliablePieces.joinToString("+") { it.source },
            reason = "Combined lethal threat (${confidence.percent()}): ${reliablePieces.joinToString(" + ") { it.reason }}",
            confidence = confidence,
            ticksUntilImpact = reliablePieces.minOf { it.ticksUntilImpact },
            position = snapshot.player.position
        )
    }

    private fun lethalDamageThreshold(effectiveHealth: Double): Double =
        effectiveHealth * GrimSaverConfig.lethalThreshold + GrimSaverConfig.safetyMargin

    private fun projectileConfidence(projectile: ProjectileSnapshot, ticksUntilImpact: Int, damage: Double, effectiveHealth: Double): Double {
        val type = projectile.typeName.lowercase()
        val base = when {
            "arrow" in type || "trident" in type -> 0.93
            "firework" in type -> 0.90
            "potion" in type -> 0.88
            "fireball" in type || "wind_charge" in type -> 0.87
            else -> 0.78
        }
        val timing = when {
            ticksUntilImpact <= 5 -> 0.04
            ticksUntilImpact <= 15 -> 0.025
            ticksUntilImpact <= 40 -> 0.0
            else -> -0.08
        }
        val speed = if (projectile.velocity.lengthSqr() > 0.01) 0.01 else -0.10
        return (base + timing + speed + lethalMarginBonus(damage, effectiveHealth)).coerceIn(0.0, 0.99)
    }

    private fun meleeConfidence(attacker: LivingSnapshot, inRange: Boolean, targeting: Boolean, distance: Double, reach: Double, damage: Double, effectiveHealth: Double): Double {
        val base = if (attacker.isPlayer) 0.76 else 0.82
        val targetingBonus = if (targeting) 0.08 else 0.0
        val rangeBonus = when {
            !inRange -> -0.20
            distance <= reach * 0.65 -> 0.07
            else -> 0.02
        }
        val attributePenalty = if (attacker.attackDamage <= 0.0 && attacker.mainHand.isEmpty) -0.12 else 0.0
        return (base + targetingBonus + rangeBonus + attributePenalty + lethalMarginBonus(damage, effectiveHealth)).coerceIn(0.0, 0.98)
    }

    private fun fallConfidence(player: PlayerSnapshot, damage: Double): Double {
        val fallingFast = if (player.velocity.y < -0.35) 0.04 else -0.04
        val fallDistance = if (player.fallDistance > player.safeFallDistance + 3.0) 0.04 else -0.02
        return (0.90 + fallingFast + fallDistance + lethalMarginBonus(damage, player.effectiveHealth)).coerceIn(0.0, 0.99)
    }

    private fun lethalMarginBonus(damage: Double, effectiveHealth: Double): Double {
        val overkill = damage - effectiveHealth
        if (overkill <= 0.0) return -0.12
        return min(0.08, overkill / max(1.0, effectiveHealth) * 0.18)
    }

    private fun DamagePrediction.reasonWithConfidence(confidence: Double): String = "$reason confidence=${confidence.percent()}"

    private fun Double.percent(): String = "${(this * 100.0).toInt()}%"

    private fun ProjectileSnapshot.kind(): ThreatKind {
        val type = typeName.lowercase()
        return when {
            "arrow" in type -> ThreatKind.LETHAL_ARROW
            "trident" in type -> ThreatKind.LETHAL_TRIDENT
            "potion" in type -> ThreatKind.LETHAL_POTION
            "firework" in type -> ThreatKind.LETHAL_FIREWORK
            else -> ThreatKind.LETHAL_PROJECTILE
        }
    }
}
