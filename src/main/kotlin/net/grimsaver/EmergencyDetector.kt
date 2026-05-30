package net.grimsaver

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.phys.Vec3
import kotlin.math.max

class EmergencyDetector {
    private var lastEffectiveHealth: Double? = null
    private var lastDamageMillis = 0L
    private var lastDamageAmount = 0.0
    private var lastDamageKind = DamageKind.UNKNOWN
    private var deathFailsafeUsed = false
    private var lastServerKey: String? = null
    private var lastDimension: String? = null
    private var lastPlayerId: Int? = null

    fun detect(client: Minecraft, snapshot: WorldSnapshot): Threat? {
        val player = client.player ?: return null
        observeSnapshotContext(snapshot)
        val now = System.currentTimeMillis()
        val effectiveHealth = snapshot.player.effectiveHealth
        val previousHealth = lastEffectiveHealth
        val healthDrop = if (previousHealth != null) (previousHealth - effectiveHealth).coerceAtLeast(0.0) else 0.0
        val inferredKind = inferDamageKind(player, snapshot)
        if (healthDrop > 0.01 || player.recentlyHurt()) {
            val previousRecentDamage = now - lastDamageMillis <= recentDamageWindowMillis()
            lastDamageAmount = max(healthDrop, lastDamageAmount.takeIf { previousRecentDamage } ?: 0.0)
            lastDamageMillis = now
            lastDamageKind = inferredKind
        }
        lastEffectiveHealth = effectiveHealth

        deathFailsafe(player, snapshot)?.let { return it }
        if (!GrimSaverConfig.criticalHealthEmergency) return null

        val criticalHp = GrimSaverConfig.criticalHealthHearts * 2.0
        val ultraHp = GrimSaverConfig.ultraCriticalHearts * 2.0
        val recentDamage = now - lastDamageMillis <= recentDamageWindowMillis()
        val environment = environmentDanger(player, snapshot)
        val creeper = creeperEmergency(snapshot, criticalHp)
        val swarm = swarmEmergency(snapshot)
        val burst = burstEmergency(snapshot, previousHealth, healthDrop, recentDamage)
        val hostile = nearestHostileDanger(snapshot)
        val passiveContact = nearestPassiveContact(snapshot)
        predictedIncomingThreat(snapshot)?.let { return it }

        burst?.let { return it }
        creeper?.let { return it }
        swarm?.let { return it }

        if (effectiveHealth <= ultraHp && GrimSaverConfig.ultraCriticalAnyDamage) {
            when {
                recentDamage -> return emergencyThreat(ThreatKind.CRITICAL_DAMAGE, snapshot, "Ultra critical after ${lastDamageKind.label} damage", 0.99, lastDamageAmount)
                environment != null -> return emergencyThreat(ThreatKind.CRITICAL_HEALTH, snapshot, "Ultra critical environmental danger: $environment", 0.98)
                hostile != null -> return emergencyThreat(ThreatKind.CRITICAL_HEALTH, snapshot, "Ultra critical near ${hostile.typeName}", 0.97)
                passiveContact != null -> return emergencyThreat(ThreatKind.PASSIVE_ENTITY_DANGER, snapshot, "Ultra critical contact with ${passiveContact.typeName}", 0.95)
            }
        }

        if (effectiveHealth <= criticalHp) {
            when {
                recentDamage && lastDamageKind.dangerous -> return emergencyThreat(ThreatKind.CRITICAL_DAMAGE, snapshot, "Critical health after ${lastDamageKind.label} damage", 0.97, lastDamageAmount)
                environment != null -> return emergencyThreat(ThreatKind.CRITICAL_HEALTH, snapshot, "Critical health environmental danger: $environment", 0.96)
                hostile != null && (hostile.targetId == snapshot.playerId || hostile.distanceToPlayer(snapshot) <= hostile.attackRange + 1.5) -> {
                    return emergencyThreat(ThreatKind.CRITICAL_HEALTH, snapshot, "Critical health near ${hostile.typeName}", 0.96)
                }
                snapshot.projectiles.any { it.predictedImpact(snapshot) != null } -> {
                    return emergencyThreat(ThreatKind.CRITICAL_HEALTH, snapshot, "Critical health with incoming projectile", 0.95)
                }
            }
        }

        return null
    }

    private fun observeSnapshotContext(snapshot: WorldSnapshot) {
        if (lastServerKey != snapshot.serverKey || lastDimension != snapshot.dimension || lastPlayerId != snapshot.playerId) {
            resetRuntimeState("context-change:${snapshot.serverKey}/${snapshot.dimension}/${snapshot.playerId}")
            lastServerKey = snapshot.serverKey
            lastDimension = snapshot.dimension
            lastPlayerId = snapshot.playerId
        }
    }

    fun resetRuntimeState(reason: String) {
        debugGrimSaver("Resetting EmergencyDetector runtime state ({})", reason)
        lastEffectiveHealth = null
        lastDamageMillis = 0L
        lastDamageAmount = 0.0
        lastDamageKind = DamageKind.UNKNOWN
        deathFailsafeUsed = false
    }

    private fun deathFailsafe(player: LocalPlayer, snapshot: WorldSnapshot): Threat? {
        if (!GrimSaverConfig.deathFailsafeEnabled) return null
        val alive = runCatching { player.isAlive }.getOrDefault(false)
        val alreadyDead = snapshot.player.health <= 0.0 || !alive
        if (!alreadyDead) {
            deathFailsafeUsed = false
            return null
        }
        if (!deathFailsafeUsed) {
            debugGrimSaver("Skipping death failsafe /sethome because the player is already dead; command dispatch would be unreliable")
        }
        deathFailsafeUsed = true
        return null
    }

    private fun creeperEmergency(snapshot: WorldSnapshot, criticalHp: Double): Threat? {
        if (!GrimSaverConfig.creeperEmergencyEnabled || snapshot.player.effectiveHealth > criticalHp) return null
        val creeper = snapshot.livingEntities.firstOrNull { creeper ->
            creeper.isCreeper && creeper.creeperSwelling && (creeper.targetId == snapshot.playerId || creeper.distanceToPlayer(snapshot) <= 5.0)
        } ?: return null
        return emergencyThreat(ThreatKind.CREEPER_EMERGENCY, snapshot, "Critical health near fusing creeper", 0.99, source = creeper.typeName)
    }

    private fun swarmEmergency(snapshot: WorldSnapshot): Threat? {
        if (!GrimSaverConfig.swarmTriggerEnabled || snapshot.player.effectiveHealth > GrimSaverConfig.swarmHealthThreshold) return null
        val radiusSqr = 10.0 * 10.0
        val hostileCount = snapshot.livingEntities.count { it.isEnemy && it.position.distanceToSqr(snapshot.player.position) <= radiusSqr }
        if (hostileCount < GrimSaverConfig.swarmMobThreshold) return null
        return emergencyThreat(ThreatKind.SWARM_EMERGENCY, snapshot, "Critical health surrounded by $hostileCount hostile mobs", 0.97, source = "hostile_swarm")
    }

    private fun burstEmergency(snapshot: WorldSnapshot, previousHealth: Double?, healthDrop: Double, recentDamage: Boolean): Threat? {
        if (!GrimSaverConfig.burstDamageEnabled || previousHealth == null || !recentDamage) return null
        val criticalHp = GrimSaverConfig.criticalHealthHearts * 2.0
        val safeToCritical = previousHealth > criticalHp && snapshot.player.effectiveHealth <= criticalHp
        val largeBurst = healthDrop >= snapshot.player.maxHealth * GrimSaverConfig.burstDamagePercentThreshold
        if (!safeToCritical && !largeBurst) return null
        return emergencyThreat(ThreatKind.BURST_DAMAGE, snapshot, "Burst damage dropped health ${"%.1f".format(healthDrop)} HP", 0.98, healthDrop)
    }

    private fun nearestHostileDanger(snapshot: WorldSnapshot): LivingSnapshot? = snapshot.livingEntities
        .asSequence()
        .filter { it.isEnemy || it.targetId == snapshot.playerId || it.isCreeper }
        .filter { it.distanceToPlayer(snapshot) <= max(6.0, it.attackRange + 2.0) }
        .minByOrNull { it.distanceToPlayer(snapshot) }

    private fun nearestPassiveContact(snapshot: WorldSnapshot): LivingSnapshot? = snapshot.livingEntities
        .asSequence()
        .filter { !it.isEnemy && !it.isPlayer && it.canBeDangerousPassive() }
        .filter { it.boundingBox.inflate(0.6).intersects(snapshot.player.boundingBox) || it.distanceToPlayer(snapshot) <= 1.6 }
        .minByOrNull { it.distanceToPlayer(snapshot) }

    private fun LivingSnapshot.canBeDangerousPassive(): Boolean {
        val type = typeName.lowercase()
        return listOf("wolf", "bee", "goat", "golem", "llama", "polar_bear", "panda").any(type::contains)
    }

    private fun environmentDanger(player: LocalPlayer, snapshot: WorldSnapshot): String? = when {
        runCatching { player.isInLava }.getOrDefault(false) -> "lava"
        runCatching { player.isOnFire }.getOrDefault(false) -> "fire"
        runCatching { player.airSupply <= 0 }.getOrDefault(false) -> "drowning"
        runCatching { player.hasEffect(MobEffects.POISON) }.getOrDefault(false) && snapshot.player.effectiveHealth <= GrimSaverConfig.ultraCriticalHearts * 2.0 -> "poison-low-health"
        runCatching { player.hasEffect(MobEffects.WITHER) }.getOrDefault(false) -> "wither"
        snapshot.player.velocity.y < -0.45 && snapshot.player.fallDistance > snapshot.player.safeFallDistance + 2.0 -> "falling"
        else -> null
    }

    private fun inferDamageKind(player: LocalPlayer, snapshot: WorldSnapshot): DamageKind {
        val candidates = listOf(
            DamageKind.ENVIRONMENT to if (environmentDanger(player, snapshot) != null) 0.75 else 0.0,
            DamageKind.PROJECTILE to (snapshot.projectiles.mapNotNull { it.predictedImpact(snapshot) }.minOfOrNull { 1.0 - (it.ticks / GrimSaverConfig.projectileLookaheadTicks.toDouble()).coerceIn(0.0, 1.0) } ?: 0.0),
            DamageKind.EXPLOSION to (snapshot.livingEntities.filter { it.isCreeper && it.creeperSwelling && it.distanceToPlayer(snapshot) <= 6.0 }.maxOfOrNull { 1.0 - (it.distanceToPlayer(snapshot) / 6.0).coerceIn(0.0, 1.0) } ?: 0.0),
            DamageKind.MELEE to (snapshot.livingEntities.filter { it.targetId == snapshot.playerId || it.distanceToPlayer(snapshot) <= it.attackRange + 0.75 }.maxOfOrNull { 1.0 - (it.distanceToPlayer(snapshot) / (it.attackRange + 1.0)).coerceIn(0.0, 1.0) } ?: 0.0)
        )
        return candidates.maxByOrNull { it.second }?.takeIf { it.second > 0.0 }?.first ?: DamageKind.UNKNOWN
    }

    private fun LocalPlayer.recentlyHurt(): Boolean = runCatching { hurtTime > 0 || invulnerableTime > 0 }.getOrDefault(false)

    private fun recentDamageWindowMillis(): Long = GrimSaverConfig.recentDamageWindowTicks * 50L

    private fun predictedIncomingThreat(snapshot: WorldSnapshot): Threat? {
        val incoming = snapshot.projectiles.mapNotNull { projectile ->
            val impact = projectile.predictedImpact(snapshot) ?: return@mapNotNull null
            val prediction = DamagePredictor.projectile(projectile, snapshot.player)
            IncomingDamage(impact.ticks, prediction.damage, prediction.source, prediction.confidence * (1.0 - (impact.ticks / GrimSaverConfig.projectileLookaheadTicks.toDouble()).coerceIn(0.0, 0.5)), projectile.id)
        }.filter { it.ticksUntilImpact <= 40 }
        if (incoming.isEmpty()) return null
        val combinedDamage = incoming.sumOf { it.damage }
        val combinedConfidence = incoming.maxOf { it.confidence }
        val netHealth = snapshot.player.effectiveHealth + snapshot.player.regenerationPerSecond * 2.0
        if (combinedDamage < netHealth + GrimSaverConfig.safetyMargin || combinedConfidence < GrimSaverConfig.lethalConfidenceThreshold * 0.75) return null
        val first = incoming.minBy { it.ticksUntilImpact }
        return Threat(
            kind = ThreatKind.LETHAL_PROJECTILE,
            damage = combinedDamage,
            health = snapshot.player.effectiveHealth,
            source = first.source,
            reason = "Predicted incoming projectile damage ${"%.1f".format(combinedDamage)} in ${first.ticksUntilImpact} ticks confidence=${(combinedConfidence * 100.0).toInt()}%",
            confidence = combinedConfidence,
            ticksUntilImpact = first.ticksUntilImpact,
            position = snapshot.player.position,
            predictedDamage = combinedDamage,
            lethalProbability = (combinedDamage / netHealth.coerceAtLeast(1.0) * combinedConfidence).coerceIn(0.0, 1.0),
            sourceEntityId = first.sourceEntityId
        )
    }

    private fun ProjectileSnapshot.predictedImpact(snapshot: WorldSnapshot): TrajectoryHelper.Impact? =
        TrajectoryHelper.firstPlayerIntersection(this, snapshot.player.boundingBox, GrimSaverConfig.projectileLookaheadTicks)

    private data class IncomingDamage(
        val ticksUntilImpact: Int,
        val damage: Double,
        val source: String,
        val confidence: Double,
        val sourceEntityId: Int?
    )

    private fun emergencyThreat(
        kind: ThreatKind,
        snapshot: WorldSnapshot,
        reason: String,
        confidence: Double,
        damage: Double = snapshot.player.effectiveHealth + GrimSaverConfig.safetyMargin + 1.0,
        source: String = reason.substringBefore(':')
    ): Threat {
        val weightedConfidence = weightedEmergencyConfidence(kind, snapshot, confidence, source, reason)
        return Threat(
            kind = kind,
            damage = max(damage, snapshot.player.effectiveHealth + GrimSaverConfig.safetyMargin + 1.0),
            health = snapshot.player.effectiveHealth,
            source = source,
            reason = "$reason confidence=${(weightedConfidence * 100.0).toInt()}%",
            confidence = weightedConfidence,
            position = snapshot.player.position
        )
    }

    private fun weightedEmergencyConfidence(kind: ThreatKind, snapshot: WorldSnapshot, baseConfidence: Double, source: String, reason: String): Double {
        val maxHealth = snapshot.player.maxHealth.coerceAtLeast(1.0)
        val heartPressure = (1.0 - snapshot.player.effectiveHealth / maxHealth).coerceIn(0.0, 1.0)
        val playerHeartsBias = 0.60 + heartPressure * 0.40
        val creeperExplosionBias = if (kind == ThreatKind.CREEPER_EMERGENCY || "creeper" in source.lowercase() || "creeper" in reason.lowercase()) 0.10 else 0.0
        val burstBias = if (kind == ThreatKind.BURST_DAMAGE || kind == ThreatKind.CRITICAL_DAMAGE) 0.05 else 0.0
        return (baseConfidence * playerHeartsBias + creeperExplosionBias + burstBias).coerceIn(0.0, 1.0)
    }

    private fun LivingSnapshot.distanceToPlayer(snapshot: WorldSnapshot): Double = position.distanceTo(snapshot.player.position)

    private enum class DamageKind(val label: String, val dangerous: Boolean) {
        MELEE("melee", true),
        PROJECTILE("projectile", true),
        EXPLOSION("explosion", true),
        ENVIRONMENT("environmental", true),
        UNKNOWN("unknown", false)
    }
}
