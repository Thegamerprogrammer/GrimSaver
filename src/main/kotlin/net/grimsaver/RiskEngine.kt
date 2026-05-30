package net.grimsaver

import net.grimsaver.EnchantmentLevels.level
import net.minecraft.core.component.DataComponents
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MaceItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class RiskEngine(private val config: GrimSaverConfig = GrimSaverConfig) {
    private val healthVelocityTracker = HealthVelocityTracker(config)
    private val burstDetectionSystem = BurstDetectionSystem(config)
    private val confidenceModel = WeightedConfidenceModel(config)
    private val combatAnalyzer = EntityCombatAnalyzer(config)
    private val projectileEngine = ProjectileTrajectoryEngine(config, combatAnalyzer)
    private val threatRegistry = UniversalThreatRegistry(config, combatAnalyzer)
    private val correlationEngine = ThreatCorrelationEngine(config)
    private val forecastEngine = HealthForecastEngine(config)

    fun assess(snapshot: WorldSnapshot): RiskAssessment {
        val history = healthVelocityTracker.record(snapshot)
        val burstLevel = burstDetectionSystem.detect(snapshot, history)
        val projectileRisks = if (config.projectileThreats) projectileEngine.analyze(snapshot) else emptyList()
        val registryRisks = threatRegistry.collect(snapshot)
        val fallRisk = if (config.fallThreats) combatAnalyzer.fallRisk(snapshot.player) else CombatRisk.none()
        val allCombatRisks = registryRisks + fallRisk.takeIf { it.predictedDamage > 0.0 }.orEmpty()
        val combatWindow = CombatWindow.from(snapshot.player, projectileRisks, allCombatRisks, environmentalDamageSources(snapshot.player))
        val correlatedWindow = correlationEngine.correlate(combatWindow)
        val forecast = forecastEngine.forecast(snapshot.player, correlatedWindow, max(0.5, config.safetyMargin))
        val predictedIncomingDamage = correlatedWindow.totalDamageWithin(config.healthForecastTicks)
        val environmentalDamage = correlatedWindow.sources.filter { it.kind == DamageSourceKind.ENVIRONMENT }.sumOf { it.damage }
        val bestProjectile = projectileRisks.maxWithOrNull(compareBy<ProjectileRisk> { it.confidence }.thenBy { it.predictedDamage })
        val bestCombat = allCombatRisks.maxByOrNull { it.weightedDamagePressure(snapshot.player.effectiveHealth) }
        val primaryDamageSource = correlatedWindow.sources.maxWithOrNull(compareBy<TimedDamage> { it.damage }.thenBy { it.confidence })
        val damageScore = pressure(snapshot.player.effectiveHealth - forecast.predictedMinimumHealth, snapshot.player.effectiveHealth + forecast.survivalThreshold)
        val targetingScore = allCombatRisks.maxOfOrNull { it.targetingScore } ?: 0.0
        val enchantmentScore = allCombatRisks.maxOfOrNull { it.enchantmentRiskScore } ?: 0.0
        val trajectoryScore = bestProjectile?.confidence ?: 0.0
        val confidence = confidenceModel.confidence(history, burstLevel, damageScore, targetingScore, enchantmentScore, trajectoryScore)
        val burstOverride = burstLevel.ordinal >= BurstLevel.CRITICAL.ordinal
        val healthPredictionLethal = forecast.predictedMinimumHealth <= forecast.survivalThreshold
        val shouldTrigger = config.enabled && (healthPredictionLethal || burstOverride) && confidence >= confidenceFloor(burstLevel, healthPredictionLethal)
        val source = when {
            burstOverride -> "health_burst"
            primaryDamageSource != null -> primaryDamageSource.source
            bestProjectile != null && (bestCombat == null || bestProjectile.predictedDamage >= bestCombat.predictedDamage) -> bestProjectile.source
            bestCombat != null -> bestCombat.source
            else -> "none"
        }
        val sourceEntityId = primaryDamageSource?.sourceEntityId ?: when {
            bestProjectile != null && (bestCombat == null || bestProjectile.predictedDamage >= bestCombat.predictedDamage) -> bestProjectile.sourceEntityId
            bestCombat != null -> bestCombat.sourceEntityId
            else -> null
        }
        val trace = ThreatTrace(
            forecast = forecast,
            combatWindow = correlatedWindow,
            primaryThreat = source,
            secondaryThreat = correlatedWindow.sources
                .filter { it.source != source }
                .maxWithOrNull(compareBy<TimedDamage> { it.damage }.thenBy { it.confidence })
                ?.source,
            rejectedThreats = correlatedWindow.rejections,
            trigger = shouldTrigger
        )
        return RiskAssessment(
            totalRiskScore = ((damageScore * 0.55) + ((1.0 - forecast.survivalProbability) * 0.45)).coerceIn(0.0, 1.0),
            confidence = if (burstLevel == BurstLevel.LETHAL) 1.0 else confidence,
            burstLevel = burstLevel,
            predictedDamage = if (burstOverride) max(predictedIncomingDamage, snapshot.player.effectiveHealth + config.safetyMargin + 1.0) else predictedIncomingDamage,
            healthVelocity = history.velocity,
            lethalProbability = if (burstLevel == BurstLevel.LETHAL) 1.0 else (1.0 - forecast.survivalProbability).coerceIn(0.0, 1.0),
            primaryThreatSource = source,
            shouldTriggerSetHome = shouldTrigger,
            predictedRemainingHealth = forecast.predictedMinimumHealth,
            environmentalDamage = environmentalDamage,
            sourceEntityId = sourceEntityId,
            forecast = forecast,
            trace = trace
        )
    }

    private fun environmentalDamageSources(player: PlayerSnapshot): List<TimedDamage> = player.activeEffects.mapNotNull { effect ->
        when {
            effect.matches("wither") -> TimedDamage(
                tick = 20,
                damage = min(12.0, (effect.durationTicks / 20.0) * (effect.amplifier + 1)),
                source = "wither",
                confidence = 0.92,
                kind = DamageSourceKind.ENVIRONMENT,
                reason = "Wither effect ${effect.durationTicks} ticks"
            )
            effect.matches("poison") && player.effectiveHealth <= config.criticalHealthHearts * 2.0 -> TimedDamage(
                tick = 25,
                damage = min(player.health - 1.0, (effect.durationTicks / 25.0) * (effect.amplifier + 1)).coerceAtLeast(0.0),
                source = "poison",
                confidence = 0.72,
                kind = DamageSourceKind.ENVIRONMENT,
                reason = "Poison effect at low health"
            )
            effect.matches("fire") && !player.fireImmune -> TimedDamage(
                tick = 20,
                damage = 2.0,
                source = "fire",
                confidence = 0.70,
                kind = DamageSourceKind.ENVIRONMENT,
                reason = "Fire tick"
            )
            else -> null
        }
    }

    private fun pressure(value: Double, limit: Double): Double = if (limit <= 0.0) 1.0 else (value / limit).coerceIn(0.0, 1.0)

    private fun healthCentricLethalProbability(confidence: Double, predictedRemainingHealth: Double, survivalThreshold: Double, burstLevel: BurstLevel): Double {
        val healthPressure = ((survivalThreshold - predictedRemainingHealth) / survivalThreshold.coerceAtLeast(0.5)).coerceIn(0.0, 1.0)
        val burstBonus = when (burstLevel) {
            BurstLevel.LETHAL -> 1.0
            BurstLevel.CRITICAL -> 0.35
            BurstLevel.MEDIUM -> 0.18
            BurstLevel.SMALL -> 0.06
            BurstLevel.NONE -> 0.0
        }
        return (healthPressure * 0.7 + confidence * 0.3 + burstBonus).coerceIn(0.0, 1.0)
    }

    private fun confidenceFloor(burstLevel: BurstLevel, healthPredictionLethal: Boolean): Double = when {
        burstLevel.ordinal >= BurstLevel.CRITICAL.ordinal -> 0.55
        healthPredictionLethal -> 0.20
        else -> config.lethalConfidenceThreshold
    }

    private fun environmentalDamage(player: PlayerSnapshot): Double {
        val effectDamage = player.activeEffects.sumOf { effect ->
            when {
                effect.matches("wither") -> min(12.0, (effect.durationTicks / 20.0) * (effect.amplifier + 1))
                effect.matches("poison") && player.effectiveHealth <= config.criticalHealthHearts * 2.0 -> min(player.health - 1.0, (effect.durationTicks / 25.0) * (effect.amplifier + 1)).coerceAtLeast(0.0)
                effect.matches("fire") && !player.fireImmune -> 2.0
                else -> 0.0
            }
        }
        val regenCredit = player.regenerationPerSecond * 2.0
        return (effectDamage - regenCredit).coerceAtLeast(0.0)
    }
}

data class HealthHistory(
    val lastHealth: Double,
    val lastTimestamp: Long,
    val velocity: Double,
    val burstDetected: Boolean
)

enum class BurstLevel { NONE, SMALL, MEDIUM, CRITICAL, LETHAL }

data class RiskAssessment(
    val totalRiskScore: Double,
    val confidence: Double,
    val burstLevel: BurstLevel,
    val predictedDamage: Double,
    val healthVelocity: Double,
    val lethalProbability: Double,
    val primaryThreatSource: String,
    val shouldTriggerSetHome: Boolean,
    val predictedRemainingHealth: Double = Double.POSITIVE_INFINITY,
    val environmentalDamage: Double = 0.0,
    val sourceEntityId: Int? = null,
    val forecast: HealthForecast? = null,
    val trace: ThreatTrace? = null
) {
    fun toThreat(snapshot: WorldSnapshot): Threat = Threat(
        kind = if (burstLevel.ordinal >= BurstLevel.MEDIUM.ordinal) ThreatKind.BURST_DAMAGE else ThreatKind.COMBINED,
        damage = max(predictedDamage, snapshot.player.effectiveHealth + GrimSaverConfig.safetyMargin + 1.0),
        health = snapshot.player.effectiveHealth,
        source = primaryThreatSource,
        reason = "RiskEngine ${burstLevel.name.lowercase()} risk confidence=${(confidence * 100.0).toInt()}% velocity=${"%.2f".format(healthVelocity)} hp/s predicted=${"%.1f".format(predictedDamage)} remaining=${"%.1f".format(predictedRemainingHealth)} env=${"%.1f".format(environmentalDamage)}",
        confidence = confidence,
        position = snapshot.player.position,
        predictedDamage = predictedDamage,
        lethalProbability = lethalProbability,
        sourceEntityId = sourceEntityId
    )
}

class HealthVelocityTracker(private val config: GrimSaverConfig) {
    private var lastHealth: Double? = null
    private var lastTimestamp: Long? = null
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun record(snapshot: WorldSnapshot): HealthHistory {
        val now = snapshot.capturedAtMillis
        val current = snapshot.player.effectiveHealth
        val previousHealth = lastHealth
        val previousTimestamp = lastTimestamp
        val deltaSeconds = ((now - (previousTimestamp ?: now)).coerceAtLeast(1L) / 1000.0)
        val velocity = if (previousHealth == null) 0.0 else (current - previousHealth) / deltaSeconds
        samples += now to current
        val windowMillis = config.burstPercentWindowTicks.coerceAtLeast(1) * 50L
        while (samples.size > 1 && now - samples.first().first > windowMillis) samples.removeFirst()
        val percentBurst = samples.firstOrNull()?.let { (_, health) -> health - current > snapshot.player.maxHealth * config.burstDamagePercentThreshold } ?: false
        val absoluteBurst = previousHealth != null && previousHealth - current >= config.burstAbsoluteThreshold
        val velocityBurst = velocity <= -config.burstVelocityThreshold
        lastHealth = current
        lastTimestamp = now
        return HealthHistory(previousHealth ?: current, previousTimestamp ?: now, velocity, absoluteBurst || velocityBurst || percentBurst)
    }
}

class BurstDetectionSystem(private val config: GrimSaverConfig) {
    fun detect(snapshot: WorldSnapshot, history: HealthHistory): BurstLevel {
        if (!config.burstDamageEnabled || !history.burstDetected) return BurstLevel.NONE
        val current = snapshot.player.effectiveHealth
        val drop = (history.lastHealth - current).coerceAtLeast(0.0)
        val percent = if (snapshot.player.maxHealth > 0.0) drop / snapshot.player.maxHealth else 0.0
        return when {
            current <= 0.0 || drop >= current + snapshot.player.absorption || history.velocity <= -config.lethalBurstVelocityThreshold -> BurstLevel.LETHAL
            drop >= config.burstCriticalThreshold || percent >= 0.65 || current <= config.ultraCriticalHearts * 2.0 -> BurstLevel.CRITICAL
            drop >= config.burstMediumThreshold || percent >= 0.35 || current <= config.criticalHealthHearts * 2.0 -> BurstLevel.MEDIUM
            drop > 0.0 -> BurstLevel.SMALL
            else -> BurstLevel.NONE
        }
    }
}

class WeightedConfidenceModel(private val config: GrimSaverConfig) {
    fun confidence(
        healthHistory: HealthHistory,
        burstLevel: BurstLevel,
        damagePredictionScore: Double,
        targetingScore: Double,
        enchantmentRiskScore: Double,
        projectileTrajectoryRisk: Double
    ): Double {
        val healthVelocityScore = (-healthHistory.velocity / config.burstVelocityThreshold.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
        val burstScore = when (burstLevel) {
            BurstLevel.NONE -> 0.0
            BurstLevel.SMALL -> 0.35
            BurstLevel.MEDIUM -> 0.65
            BurstLevel.CRITICAL -> 0.9
            BurstLevel.LETHAL -> 1.0
        }
        val weighted = config.healthVelocityWeight * healthVelocityScore +
            config.burstWeight * burstScore +
            config.damagePredictionWeight * damagePredictionScore.coerceIn(0.0, 1.0) +
            config.targetingWeight * targetingScore.coerceIn(0.0, 1.0) +
            config.enchantmentWeight * enchantmentRiskScore.coerceIn(0.0, 1.0) +
            config.trajectoryWeight * projectileTrajectoryRisk.coerceIn(0.0, 1.0)
        val totalWeight = config.healthVelocityWeight + config.burstWeight + config.damagePredictionWeight + config.targetingWeight + config.enchantmentWeight + config.trajectoryWeight
        return if (totalWeight <= 0.0) 0.0 else (weighted / totalWeight).coerceIn(0.0, 1.0)
    }

    fun lethalProbability(confidence: Double, predictedDamage: Double, effectiveHealth: Double, burstLevel: BurstLevel): Double {
        val burstBonus = when (burstLevel) {
            BurstLevel.LETHAL -> 1.0
            BurstLevel.CRITICAL -> 0.35
            BurstLevel.MEDIUM -> 0.18
            BurstLevel.SMALL -> 0.08
            BurstLevel.NONE -> 0.0
        }
        val damagePressure = if (effectiveHealth <= 0.0) 1.0 else predictedDamage / (effectiveHealth + config.safetyMargin)
        return (confidence * 0.65 + damagePressure.coerceIn(0.0, 1.0) * 0.35 + burstBonus).coerceIn(0.0, 1.0)
    }
}

data class ProjectileRisk(
    val ticksToImpact: Int,
    val predictedDamage: Double,
    val confidence: Double,
    val source: String,
    val sourceEntityId: Int? = null
)

class ProjectileTrajectoryEngine(private val config: GrimSaverConfig, private val combatAnalyzer: EntityCombatAnalyzer) {
    fun analyze(snapshot: WorldSnapshot): List<ProjectileRisk> = snapshot.projectiles.mapNotNull { projectile ->
        val impact = simulate(projectile, snapshot.player.boundingBox, config.projectileLookaheadTicks) ?: return@mapNotNull null
        val prediction = combatAnalyzer.projectileDamage(projectile, snapshot.player)
        val timing = when {
            impact.ticksToImpact <= 5 -> 0.10
            impact.ticksToImpact <= 15 -> 0.05
            else -> 0.0
        }
        val confidence = ((baseProjectileConfidence(projectile) * 0.35) + (prediction.confidence * 0.45) + timing + (prediction.damage / max(1.0, snapshot.player.effectiveHealth)) * 0.12).coerceIn(0.0, 1.0)
        ProjectileRisk(impact.ticksToImpact, prediction.damage, confidence, prediction.source, projectile.ownerId ?: projectile.id)
    }

    private fun simulate(projectile: ProjectileSnapshot, playerBox: AABB, maxTicks: Int): ProjectileImpact? {
        if (projectile.inGround || projectile.velocity.lengthSqr() < 0.0001) return null
        var pos = projectile.position
        var velocity = projectile.velocity
        val info = projectileInfo(projectile)
        val expanded = playerBox.inflate(0.35 + info.hitboxRadius)
        repeat(maxTicks) { tick ->
            val next = pos.add(velocity)
            if (expanded.clip(pos, next).isPresent) return ProjectileImpact(tick + 1)
            pos = next
            velocity = velocity.scale(info.drag).subtract(0.0, info.gravity, 0.0)
        }
        return null
    }

    private fun projectileInfo(projectile: ProjectileSnapshot): ProjectileInfo {
        val type = projectile.typeName.lowercase()
        return when {
            "arrow" in type || "trident" in type -> ProjectileInfo(projectile.gravity, 0.5, 0.99)
            "potion" in type -> ProjectileInfo(projectile.gravity, 0.25, 0.99)
            "firework" in type -> ProjectileInfo(projectile.gravity, 0.25, 1.0)
            "fireball" in type || "wind_charge" in type -> ProjectileInfo(projectile.gravity, 1.0, 1.0)
            else -> ProjectileInfo(projectile.gravity, config.moddedProjectileHitboxRadius, config.moddedProjectileDrag)
        }
    }

    private fun baseProjectileConfidence(projectile: ProjectileSnapshot): Double {
        val type = projectile.typeName.lowercase()
        return when {
            "arrow" in type || "trident" in type -> 0.86
            "firework" in type -> 0.84
            "potion" in type -> 0.8
            "fireball" in type || "wind_charge" in type -> 0.78
            else -> 0.68
        }
    }

    private data class ProjectileImpact(val ticksToImpact: Int)
    private data class ProjectileInfo(val gravity: Double, val hitboxRadius: Double, val drag: Double)
}

data class CombatRisk(
    val predictedDamage: Double,
    val targetingScore: Double,
    val enchantmentRiskScore: Double,
    val source: String,
    val reason: String,
    val sourceEntityId: Int? = null,
    val maximumDamage: Double = predictedDamage,
    val attackIntervalTicks: Int = 20,
    val specialEffects: List<String> = emptyList(),
    val ticksUntilImpact: Int = attackIntervalTicks
) {
    fun weightedDamagePressure(effectiveHealth: Double): Double = predictedDamage / max(1.0, effectiveHealth) + targetingScore * 0.25 + enchantmentRiskScore * 0.1
    companion object {
        fun none() = CombatRisk(0.0, 0.0, 0.0, "none", "No threat")
    }
}


data class HealthForecast(
    val currentHealth: Double,
    val predictedMinimumHealth: Double,
    val predictedHealthTimeline: List<Double>,
    val survivalProbability: Double,
    val lethalTick: Int?,
    val survivalThreshold: Double
)

data class CombatWindow(
    val horizonTicks: Int,
    val sources: List<TimedDamage>,
    val rejections: List<ThreatRejection> = emptyList()
) {
    fun totalDamageWithin(ticks: Int): Double = sources.filter { it.tick <= ticks }.sumOf { it.damage }

    companion object {
        fun from(player: PlayerSnapshot, projectiles: List<ProjectileRisk>, combat: List<CombatRisk>, environmental: List<TimedDamage>): CombatWindow {
            val projectileSources = projectiles.map { projectile ->
                TimedDamage(
                    tick = projectile.ticksToImpact,
                    damage = projectile.predictedDamage,
                    source = projectile.source,
                    confidence = projectile.confidence,
                    kind = DamageSourceKind.PROJECTILE,
                    sourceEntityId = projectile.sourceEntityId,
                    reason = "Predicted projectile impact"
                )
            }
            val combatSources = combat.map { risk ->
                TimedDamage(
                    tick = risk.ticksUntilImpact.coerceAtLeast(1),
                    damage = risk.predictedDamage,
                    source = risk.source,
                    confidence = (0.45 + risk.targetingScore * 0.4 + risk.enchantmentRiskScore * 0.15).coerceIn(0.0, 1.0),
                    kind = DamageSourceKind.ENTITY,
                    sourceEntityId = risk.sourceEntityId,
                    reason = risk.reason,
                    maximumDamage = risk.maximumDamage,
                    specialEffects = risk.specialEffects
                )
            }
            return CombatWindow(GrimSaverConfig.healthForecastTicks, projectileSources + combatSources + environmental)
        }
    }
}

data class TimedDamage(
    val tick: Int,
    val damage: Double,
    val source: String,
    val confidence: Double,
    val kind: DamageSourceKind,
    val sourceEntityId: Int? = null,
    val reason: String = "",
    val maximumDamage: Double = damage,
    val specialEffects: List<String> = emptyList()
)

enum class DamageSourceKind { PROJECTILE, ENTITY, ENVIRONMENT, FALL }

data class ThreatRejection(
    val entity: String,
    val reason: String,
    val impactProbability: Double,
    val requiredProbability: Double
)

data class ThreatTrace(
    val forecast: HealthForecast,
    val combatWindow: CombatWindow,
    val primaryThreat: String,
    val secondaryThreat: String?,
    val rejectedThreats: List<ThreatRejection>,
    val trigger: Boolean
)

class HealthForecastEngine(private val config: GrimSaverConfig) {
    fun forecast(player: PlayerSnapshot, window: CombatWindow, survivalThreshold: Double): HealthForecast {
        val horizon = config.healthForecastTicks.coerceIn(20, 200)
        val damageByTick = window.sources
            .filter { it.tick in 1..horizon && it.confidence >= 0.20 }
            .groupBy { it.tick }
            .mapValues { (_, hits) -> hits.sumOf { it.damage } }
        val timeline = ArrayList<Double>(horizon + 1)
        var health = player.effectiveHealth
        var minimum = health
        var lethalTick: Int? = null
        timeline += health
        for (tick in 1..horizon) {
            health += player.regenerationPerSecond / 20.0
            health -= damageByTick[tick] ?: 0.0
            minimum = min(minimum, health)
            if (lethalTick == null && health <= survivalThreshold) lethalTick = tick
            timeline += health
        }
        val deficit = (survivalThreshold - minimum).coerceAtLeast(0.0)
        val survivalProbability = (1.0 - deficit / max(1.0, player.effectiveHealth)).coerceIn(0.0, 1.0)
        return HealthForecast(player.effectiveHealth, minimum, timeline, survivalProbability, lethalTick, survivalThreshold)
    }
}

class ThreatCorrelationEngine(private val config: GrimSaverConfig) {
    fun correlate(window: CombatWindow): CombatWindow {
        val requiredProbability = (config.lethalConfidenceThreshold * 0.55).coerceIn(0.25, 0.75)
        val accepted = mutableListOf<TimedDamage>()
        val rejected = mutableListOf<ThreatRejection>()
        for (source in window.sources) {
            if (source.confidence < requiredProbability && source.damage < 4.0) {
                rejected += ThreatRejection(source.source, "Low Impact Probability", source.confidence, requiredProbability)
            } else {
                accepted += source
            }
        }
        val correlated = accepted.filter { candidate ->
            candidate.kind == DamageSourceKind.ENVIRONMENT || accepted.any { other ->
                other === candidate || abs(other.tick - candidate.tick) <= config.threatCorrelationWindowTicks
            }
        }
        return window.copy(sources = correlated, rejections = rejected)
    }
}


class UniversalThreatRegistry(
    private val config: GrimSaverConfig,
    private val combatAnalyzer: EntityCombatAnalyzer
) {
    fun collect(snapshot: WorldSnapshot): List<CombatRisk> = snapshot.livingEntities.mapNotNull { entity ->
        val profile = profile(entity) ?: return@mapNotNull null
        val intelligence = combatAnalyzer.intelligence(entity, snapshot)
        if (!entity.isPlayer && profile.passive && !intelligence.isTargetingPlayer && !profile.aggressiveState(entity, intelligence)) return@mapNotNull null
        if (entity.isPlayer && !config.pvpThreats) return@mapNotNull null
        if (!entity.isPlayer && !config.mobThreats) return@mapNotNull null
        val base = combatAnalyzer.analyze(entity, snapshot) ?: profileFallback(entity, snapshot, profile, intelligence) ?: return@mapNotNull null
        base.copy(
            sourceEntityId = entity.id,
            maximumDamage = max(base.maximumDamage, profile.maximumDamage(entity, snapshot.player)),
            attackIntervalTicks = profile.attackIntervalTicks,
            specialEffects = profile.specialEffects(entity)
        )
    }

    private fun profileFallback(entity: LivingSnapshot, snapshot: WorldSnapshot, profile: ThreatProfile, intelligence: EntityIntelligence): CombatRisk? {
        val distance = entity.position.distanceTo(snapshot.player.position)
        val pressure = when {
            intelligence.isTargetingPlayer -> 1.0
            distance <= profile.reach(entity) + config.meleeRangePadding -> 0.8
            profile.hostile && distance <= max(6.0, profile.reach(entity) + 2.0) -> 0.45
            else -> 0.0
        }
        if (pressure <= 0.0) return null
        val damage = DamagePredictor.melee(entity, snapshot.player).damage.takeIf { it > 0.0 }
            ?: profile.expectedDamage(entity, snapshot.player)
        return CombatRisk(
            predictedDamage = damage,
            targetingScore = pressure,
            enchantmentRiskScore = 0.0,
            source = entity.name.ifBlank { entity.typeName },
            reason = "Universal threat registry ${profile.key}",
            sourceEntityId = entity.id,
            maximumDamage = profile.maximumDamage(entity, snapshot.player),
            attackIntervalTicks = profile.attackIntervalTicks,
            specialEffects = profile.specialEffects(entity)
        )
    }

    private fun profile(entity: LivingSnapshot): ThreatProfile? {
        if (entity.isPlayer) return PLAYER_PROFILE
        val type = entity.typeName.lowercase()
        return PROFILES.firstOrNull { profile -> profile.aliases.any(type::contains) }
    }

    data class ThreatProfile(
        val key: String,
        val aliases: List<String>,
        val baseDamage: Double,
        val maxDamage: Double,
        val baseReach: Double,
        val attackIntervalTicks: Int,
        val hostile: Boolean = true,
        val passive: Boolean = false,
        val effectProvider: (LivingSnapshot) -> List<String> = { emptyList() }
    ) {
        fun expectedDamage(entity: LivingSnapshot, player: PlayerSnapshot): Double = max(baseDamage, entity.attackDamage).coerceAtLeast(0.0)
        fun maximumDamage(entity: LivingSnapshot, player: PlayerSnapshot): Double = max(maxDamage, max(baseDamage, entity.attackDamage) * 1.5)
        fun reach(entity: LivingSnapshot): Double = max(baseReach, entity.attackRange)
        fun specialEffects(entity: LivingSnapshot): List<String> = effectProvider(entity)
        fun aggressiveState(entity: LivingSnapshot, intelligence: EntityIntelligence): Boolean = hostile || entity.isEnemy || intelligence.sprintOrChaseBehavior
    }

    private companion object {
        val PLAYER_PROFILE = ThreatProfile("player", listOf("player"), 1.0, 30.0, 3.0, 12)
        val PROFILES = listOf(
            ThreatProfile("zombie", listOf("zombie", "husk", "drowned"), 3.0, 9.0, 2.0, 20),
            ThreatProfile("skeleton", listOf("skeleton", "stray", "bogged"), 4.0, 12.0, 16.0, 25, effectProvider = { listOf("ranged") }),
            ThreatProfile("creeper", listOf("creeper"), 20.0, 49.0, 6.0, 30, effectProvider = { listOf("explosion") }),
            ThreatProfile("spider", listOf("spider", "cave_spider"), 2.0, 7.0, 2.0, 20, effectProvider = { if (it.typeName.contains("cave", true)) listOf("poison") else emptyList() }),
            ThreatProfile("enderman", listOf("enderman"), 7.0, 14.0, 3.0, 20),
            ThreatProfile("piglin", listOf("piglin", "piglin_brute"), 5.0, 19.0, 3.0, 20),
            ThreatProfile("hoglin", listOf("hoglin", "zoglin"), 6.0, 18.0, 3.0, 20),
            ThreatProfile("blaze", listOf("blaze"), 6.0, 12.0, 16.0, 20, effectProvider = { listOf("fire") }),
            ThreatProfile("ghast", listOf("ghast"), 9.0, 25.0, 64.0, 40, effectProvider = { listOf("explosion", "fire") }),
            ThreatProfile("witch", listOf("witch"), 6.0, 18.0, 16.0, 40, effectProvider = { listOf("potion", "poison") }),
            ThreatProfile("ravager", listOf("ravager"), 12.0, 24.0, 4.0, 20),
            ThreatProfile("illager", listOf("vindicator", "evoker", "pillager"), 6.0, 15.0, 16.0, 25, effectProvider = { listOf("ranged_or_magic") }),
            ThreatProfile("shulker", listOf("shulker"), 4.0, 10.0, 24.0, 20, effectProvider = { listOf("levitation") }),
            ThreatProfile("guardian", listOf("guardian", "elder_guardian"), 6.0, 12.0, 16.0, 40, effectProvider = { listOf("laser") }),
            ThreatProfile("warden", listOf("warden"), 30.0, 45.0, 20.0, 30, effectProvider = { listOf("sonic_boom") }),
            ThreatProfile("boss", listOf("wither", "ender_dragon"), 15.0, 49.0, 32.0, 20, effectProvider = { listOf("boss") }),
            ThreatProfile("slime", listOf("slime", "magma_cube"), 4.0, 12.0, 2.0, 20, effectProvider = { if (it.typeName.contains("magma", true)) listOf("fire") else emptyList() }),
            ThreatProfile("phantom", listOf("phantom"), 6.0, 12.0, 3.0, 20),
            ThreatProfile("breeze", listOf("breeze"), 6.0, 12.0, 16.0, 20, effectProvider = { listOf("wind_charge") }),
            ThreatProfile("iron_golem", listOf("iron_golem"), 15.0, 32.0, 3.0, 20, hostile = false, passive = true),
            ThreatProfile("snow_golem", listOf("snow_golem"), 1.0, 3.0, 12.0, 20, hostile = false, passive = true),
            ThreatProfile("bee", listOf("bee"), 2.0, 6.0, 2.0, 20, hostile = false, passive = true, effectProvider = { listOf("poison") }),
            ThreatProfile("wolf", listOf("wolf"), 4.0, 8.0, 2.0, 20, hostile = false, passive = true),
            ThreatProfile("goat", listOf("goat"), 2.0, 8.0, 4.0, 40, hostile = false, passive = true, effectProvider = { listOf("ram") }),
            ThreatProfile("llama", listOf("llama", "trader_llama"), 1.0, 3.0, 10.0, 20, hostile = false, passive = true, effectProvider = { listOf("spit") }),
            ThreatProfile("dolphin", listOf("dolphin"), 2.0, 5.0, 2.0, 20, hostile = false, passive = true),
            ThreatProfile("fox", listOf("fox"), 2.0, 8.0, 2.0, 20, hostile = false, passive = true)
        )
    }
}

class EntityCombatAnalyzer(private val config: GrimSaverConfig = GrimSaverConfig) {
    fun analyze(attacker: LivingSnapshot, snapshot: WorldSnapshot): CombatRisk? {
        if (attacker.isPlayer && !config.pvpThreats) return null
        if (!attacker.isPlayer && !config.mobThreats) return null
        val intelligence = intelligence(attacker, snapshot)
        if (attacker.isCreeper && (attacker.creeperSwelling || attacker.position.distanceTo(snapshot.player.position) <= 5.0)) {
            return creeperExplosionRisk(attacker, snapshot.player, intelligence)
        }
        if (!intelligence.isAggressive && intelligence.attackRangePressure <= 0.0 && !intelligence.fallingAttack) return null
        val prediction = meleeDamage(attacker, snapshot.player, intelligence)
        return CombatRisk(prediction.damage, intelligence.targetingScore, enchantmentRisk(attacker.mainHand), prediction.source, prediction.reason, sourceEntityId = attacker.id)
    }

    fun projectileDamage(projectile: ProjectileSnapshot, player: PlayerSnapshot): DamagePrediction = DamagePredictor.projectile(projectile, player)

    private fun creeperExplosionRisk(creeper: LivingSnapshot, player: PlayerSnapshot, intelligence: EntityIntelligence): CombatRisk {
        val distance = creeper.position.distanceTo(player.position)
        val exposure = (1.0 - distance / 6.0).coerceIn(0.0, 1.0)
        val raw = 36.0 * exposure * exposure + if (creeper.creeperSwelling) 8.0 else 0.0
        val reduced = reduceWithArmorAndEnchantments(raw, player, DamageChannel.EXPLOSION)
        val targeting = max(0.85, max(intelligence.targetingScore, if (creeper.creeperSwelling) 1.0 else 0.0))
        return CombatRisk(reduced, targeting, 0.0, creeper.typeName, "RiskEngine creeper explosion pressure", sourceEntityId = creeper.id, maximumDamage = 49.0, attackIntervalTicks = 30, specialEffects = listOf("explosion"))
    }

    fun fallRisk(player: PlayerSnapshot): CombatRisk {
        val prediction = fallDamage(player)
        return CombatRisk(prediction.damage, if (prediction.damage > 0.0) 0.55 else 0.0, 0.0, prediction.source, prediction.reason)
    }

    fun meleeDamage(attacker: LivingSnapshot, player: PlayerSnapshot, intelligence: EntityIntelligence = EntityIntelligence.neutral()): DamagePrediction {
        val stack = attacker.mainHand
        var raw = max(attacker.attackDamage, itemAttackDamage(stack))
        raw *= (0.2 + attacker.attackCooldown.coerceIn(0.0, 1.0).let { it * it * 0.8 })
        raw += stack.level(Enchantments.SHARPNESS).let { if (it > 0) 0.5 * it + 0.5 else 0.0 }
        raw += max(stack.level(Enchantments.SMITE), stack.level(Enchantments.BANE_OF_ARTHROPODS)) * 2.5
        raw += attacker.activeEffects.firstOrNull { it.matches("strength") }?.let { 3.0 * (it.amplifier + 1) } ?: 0.0
        raw -= attacker.activeEffects.firstOrNull { it.matches("weakness") }?.let { 4.0 * (it.amplifier + 1) } ?: 0.0
        if (!attacker.isOnGround && attacker.fallDistance > 0.0f && attacker.velocity.y < -0.08) raw *= 1.5
        if (attacker.isSprinting) raw += 1.0
        raw += stack.level(Enchantments.FIRE_ASPECT) * 2.0
        raw += customEnchantmentRisk(stack) * 1.5
        if (intelligence.fallingAttack || stack.item == Items.MACE) raw += maceFallBonus(attacker, player, raw)
        val reduced = reduceWithArmorAndEnchantments(raw.coerceAtLeast(0.0), player, DamageChannel.MELEE)
        val entityName = attacker.name.ifBlank { attacker.typeName }
        val source = if (attacker.isPlayer) entityName else attacker.typeName.substringAfterLast('.')
        val weapon = if (stack.isEmpty) "hands" else runCatching { stack.hoverName.string }.getOrDefault("unknown item")
        return DamagePrediction(reduced, "RiskEngine melee ${source.replaceFirstChar { it.titlecase() }} with $weapon", entityName)
    }

    fun fallDamage(player: PlayerSnapshot): DamagePrediction {
        if (player.onGround || player.velocity.y >= -0.08) return DamagePrediction(0.0, "Fall Damage", "fall")
        val predictedDistance = player.fallDistance + (-player.velocity.y * 8.0) + config.fallDamageMargin
        val raw = max(0.0, ceil(predictedDistance - player.safeFallDistance) * player.fallDamageMultiplier)
        return DamagePrediction(reduceFall(raw, player), "RiskEngine fall damage", "fall")
    }

    fun intelligence(attacker: LivingSnapshot, snapshot: WorldSnapshot): EntityIntelligence {
        val toPlayer = snapshot.player.position.subtract(attacker.position)
        val distance = toPlayer.length()
        val normalized = if (distance > 0.0001) toPlayer.scale(1.0 / distance) else Vec3.ZERO
        val approachSpeed = attacker.velocity.dot(normalized)
        val inRange = distance <= attacker.attackRange + config.meleeRangePadding || attacker.boundingBox.inflate(attacker.attackRange + config.meleeRangePadding).intersects(snapshot.player.boundingBox)
        val targeting = attacker.targetId == snapshot.playerId
        val recentlyAggroed = targeting && distance <= min(attacker.followRange.coerceAtLeast(8.0), config.scanRadius)
        val chasing = approachSpeed > 0.08 && distance <= 12.0
        val verticalAdvantage = attacker.position.y - snapshot.player.position.y
        val fallingAttack = attacker.velocity.y < config.maceDownwardVelocityThreshold && verticalAdvantage >= config.maceMinimumHeightAdvantage
        val rangePressure = when {
            inRange -> 1.0
            distance <= attacker.attackRange + config.meleeRangePadding + 2.0 -> 0.65
            chasing -> 0.4
            else -> 0.0
        }
        val targetingScore = listOf(if (targeting) 1.0 else 0.0, if (recentlyAggroed) 0.85 else 0.0, if (chasing) 0.55 else 0.0, rangePressure).max()
        return EntityIntelligence(targeting, targeting || attacker.isEnemy || attacker.isCreeper, recentlyAggroed, chasing, rangePressure, verticalAdvantage, fallingAttack, targetingScore)
    }

    private fun itemAttackDamage(stack: ItemStack): Double {
        val componentDamage = runCatching {
            stack[DataComponents.ATTRIBUTE_MODIFIERS]?.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND)
        }.getOrNull()
        if (componentDamage != null && componentDamage > 1.0) return componentDamage
        val nbtDamage = stack.customDamageFromNbt()
        if (nbtDamage != null && nbtDamage > 0.0) return nbtDamage
        return when (stack.item) {
            Items.WOODEN_SWORD, Items.GOLDEN_SWORD -> 4.0
            Items.STONE_SWORD -> 5.0
            Items.IRON_SWORD -> 6.0
            Items.DIAMOND_SWORD -> 7.0
            Items.NETHERITE_SWORD -> 8.0
            Items.WOODEN_AXE, Items.GOLDEN_AXE -> 7.0
            Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE -> 9.0
            Items.NETHERITE_AXE -> 10.0
            Items.TRIDENT -> 9.0
            Items.MACE -> 6.0
            else -> when (stack.item) {
                is AxeItem -> 9.0
                is TridentItem -> 9.0
                is MaceItem -> 6.0
                else -> stack.tierDamageFallback()
            }
        }
    }

    private fun maceFallBonus(attacker: LivingSnapshot, player: PlayerSnapshot, baseDamage: Double): Double {
        val fallHeight = (attacker.position.y - player.position.y).coerceAtLeast(0.0)
        val velocityBonus = abs(attacker.velocity.y) * config.maceVelocityDamageScale
        return fallHeight * config.maceFallDamageScale + baseDamage * velocityBonus
    }

    private fun reduceWithArmorAndEnchantments(raw: Double, player: PlayerSnapshot, channel: DamageChannel): Double {
        var damage = vanillaArmorReduction(raw, player.armor, player.armorToughness)
        val epf = player.armorStacks.sumOf { armor ->
            armor.level(Enchantments.PROTECTION) +
                (if (channel == DamageChannel.PROJECTILE) armor.level(Enchantments.PROJECTILE_PROTECTION) * 2 else 0) +
                (if (channel == DamageChannel.FIRE) armor.level(Enchantments.FIRE_PROTECTION) * 2 else 0) +
                (if (channel == DamageChannel.EXPLOSION) armor.level(Enchantments.BLAST_PROTECTION) * 2 else 0) +
                customProtectionRisk(armor)
        }.coerceIn(0, 20)
        damage *= 1.0 - epf / 25.0
        player.activeEffects.firstOrNull { it.matches("resistance") }?.let { resistance ->
            damage *= 1.0 - ((resistance.amplifier + 1) * 0.2).coerceAtMost(0.8)
        }
        return damage.coerceAtLeast(0.0)
    }

    private fun reduceFall(raw: Double, player: PlayerSnapshot): Double {
        val epf = player.armorStacks.sumOf { armor -> armor.level(Enchantments.PROTECTION) + armor.level(Enchantments.FEATHER_FALLING) * 3 + customProtectionRisk(armor) }.coerceIn(0, 20)
        return (raw * (1.0 - epf / 25.0)).coerceAtLeast(0.0)
    }

    private fun vanillaArmorReduction(damage: Double, armor: Double, toughness: Double): Double {
        val armorFactor = min(20.0, max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0)))
        return damage * (1.0 - armorFactor / 25.0)
    }

    private fun enchantmentRisk(stack: ItemStack): Double = (stack.level(Enchantments.SHARPNESS) * 0.12 + stack.level(Enchantments.POWER) * 0.12 + customEnchantmentRisk(stack) * 0.15).coerceIn(0.0, 1.0)
    private fun customEnchantmentRisk(stack: ItemStack): Int = stack.nbtString().count { it == ':' }.coerceAtMost(5)
    private fun customProtectionRisk(stack: ItemStack): Int = if ("protection" in stack.nbtString().lowercase()) 1 else 0

    private fun ItemStack.customDamageFromNbt(): Double? {
        val text = nbtString()
        return sequenceOf("AttackDamage", "Damage", "custom_damage", "weapon_damage")
            .mapNotNull { key -> Regex("$key[=:\\s]+(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
            .firstOrNull()
    }

    private fun ItemStack.tierDamageFallback(): Double {
        val text = nbtString().lowercase()
        return when {
            "netherite" in text -> 8.0
            "diamond" in text -> 7.0
            "iron" in text -> 6.0
            "stone" in text -> 5.0
            "gold" in text || "wood" in text -> 4.0
            "spear" in text -> 7.0
            "halberd" in text || "battleaxe" in text -> 9.0
            "mace" in text -> 6.0
            else -> 2.0
        }
    }

    private fun ItemStack.nbtString(): String = runCatching {
        listOfNotNull(this[DataComponents.CUSTOM_DATA], this[DataComponents.ATTRIBUTE_MODIFIERS], this[DataComponents.ENCHANTMENTS], this[DataComponents.STORED_ENCHANTMENTS]).joinToString(" ")
    }.getOrDefault("")

    private enum class DamageChannel { MELEE, PROJECTILE, FIRE, EXPLOSION }
}

data class EntityIntelligence(
    val isTargetingPlayer: Boolean,
    val isAggressive: Boolean,
    val recentlyAggroed: Boolean,
    val sprintOrChaseBehavior: Boolean,
    val attackRangePressure: Double,
    val verticalAdvantage: Double,
    val fallingAttack: Boolean,
    val targetingScore: Double
) {
    companion object {
        fun neutral() = EntityIntelligence(false, false, false, false, 0.0, 0.0, false, 0.0)
    }
}

private fun <T> T?.orEmpty(): List<T> = if (this == null) emptyList() else listOf(this)
