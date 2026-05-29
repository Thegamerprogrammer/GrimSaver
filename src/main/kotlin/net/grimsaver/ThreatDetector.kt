package net.grimsaver

import kotlin.math.max

class ThreatDetector(private val homeManager: HomeManager) {
    fun detect(snapshot: WorldSnapshot): Threat? {
        val threats = mutableListOf<Threat>()
        if (GrimSaverConfig.projectileThreats) threats += projectileThreats(snapshot)
        if (GrimSaverConfig.meleeThreats) threats += meleeThreats(snapshot)
        if (GrimSaverConfig.fallThreats) fallThreat(snapshot)?.let(threats::add)

        val lethalAt = snapshot.player.effectiveHealth - GrimSaverConfig.safetyMargin
        threats.firstOrNull { it.damage >= lethalAt && homeManager.cooldownReady(it) }?.let { return it }

        if (GrimSaverConfig.combineThreats) {
            val combined = threats.sumOf { it.damage }
            if (combined >= lethalAt && threats.isNotEmpty()) {
                return Threat(
                    kind = ThreatKind.COMBINED,
                    damage = combined,
                    health = snapshot.player.effectiveHealth,
                    source = threats.joinToString("+") { it.source },
                    reason = "Combined: ${threats.joinToString(" + ") { it.reason }}",
                    ticksUntilImpact = threats.minOf { it.ticksUntilImpact },
                    position = snapshot.player.position
                ).takeIf(homeManager::cooldownReady)
            }
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
        Threat(
            kind = projectile.kind(),
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = prediction.source,
            reason = prediction.reason,
            ticksUntilImpact = impact.ticks,
            position = snapshot.player.position
        )
    }.sortedByDescending { it.damage }

    private fun meleeThreats(snapshot: WorldSnapshot): List<Threat> = snapshot.livingEntities.mapNotNull { attacker ->
        val reach = attacker.attackRange + GrimSaverConfig.meleeRangePadding
        val inRange = attacker.position.distanceTo(snapshot.player.position) <= reach || attacker.boundingBox.inflate(reach).intersects(snapshot.player.boundingBox)
        val targeting = attacker.targetId == snapshot.playerId
        if (!inRange && !targeting) return@mapNotNull null
        val prediction = DamagePredictor.melee(attacker, snapshot.player)
        Threat(
            kind = if (attacker.isPlayer) ThreatKind.MELEE_PLAYER else ThreatKind.MELEE_MOB,
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = prediction.source,
            reason = prediction.reason,
            position = snapshot.player.position
        )
    }.sortedByDescending { it.damage }

    private fun fallThreat(snapshot: WorldSnapshot): Threat? {
        val prediction = DamagePredictor.fall(snapshot.player)
        if (prediction.damage <= 0.0) return null
        return Threat(
            kind = ThreatKind.FALL_DAMAGE,
            damage = prediction.damage,
            health = snapshot.player.effectiveHealth,
            source = snapshot.dimension,
            reason = prediction.reason,
            ticksUntilImpact = max(1, (-snapshot.player.velocity.y * 2.0).toInt()),
            position = snapshot.player.position
        )
    }

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
