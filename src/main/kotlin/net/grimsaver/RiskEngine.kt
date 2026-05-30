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

    fun assess(snapshot: WorldSnapshot): RiskAssessment {
        val history = healthVelocityTracker.record(snapshot)
        val burstLevel = burstDetectionSystem.detect(snapshot, history)
        val projectileRisks = if (config.projectileThreats) projectileEngine.analyze(snapshot) else emptyList()
        val combatRisks = if (config.pvpThreats || config.mobThreats) snapshot.livingEntities.mapNotNull { combatAnalyzer.analyze(it, snapshot) } else emptyList()
        val fallRisk = if (config.fallThreats) combatAnalyzer.fallRisk(snapshot.player) else CombatRisk.none()
        val allCombatRisks = combatRisks + fallRisk.takeIf { it.predictedDamage > 0.0 }.orEmpty()
        val bestProjectile = projectileRisks.maxWithOrNull(compareBy<ProjectileRisk> { it.confidence }.thenBy { it.predictedDamage })
        val bestCombat = allCombatRisks.maxByOrNull { it.weightedDamagePressure(snapshot.player.effectiveHealth) }
        val predictedDamage = listOf(
            bestProjectile?.predictedDamage ?: 0.0,
            bestCombat?.predictedDamage ?: 0.0,
            if (config.combineThreats) allCombatRisks.sumOf { it.predictedDamage } + projectileRisks.sumOf { it.predictedDamage } else 0.0
        ).max()
        val damageScore = pressure(predictedDamage, snapshot.player.effectiveHealth + config.safetyMargin)
        val targetingScore = allCombatRisks.maxOfOrNull { it.targetingScore } ?: 0.0
        val enchantmentScore = allCombatRisks.maxOfOrNull { it.enchantmentRiskScore } ?: 0.0
        val trajectoryScore = bestProjectile?.confidence ?: 0.0
        val confidence = confidenceModel.confidence(history, burstLevel, damageScore, targetingScore, enchantmentScore, trajectoryScore)
        val lethalProbability = confidenceModel.lethalProbability(confidence, predictedDamage, snapshot.player.effectiveHealth, burstLevel)
        val burstOverride = burstLevel.ordinal >= BurstLevel.CRITICAL.ordinal
        val rapidDrop = history.velocity <= -config.burstVelocityThreshold || history.burstDetected || burstLevel.ordinal >= BurstLevel.MEDIUM.ordinal
        val lethalByDamage = predictedDamage >= snapshot.player.effectiveHealth * config.lethalThreshold + config.safetyMargin
        val shouldTrigger = config.enabled && rapidDrop && (burstOverride || (confidence >= config.lethalConfidenceThreshold && lethalByDamage))
        val source = when {
            burstOverride -> "health_burst"
            bestProjectile != null && (bestCombat == null || bestProjectile.predictedDamage >= bestCombat.predictedDamage) -> bestProjectile.source
            bestCombat != null -> bestCombat.source
            else -> "none"
        }
        return RiskAssessment(
            totalRiskScore = ((damageScore * 0.65) + (lethalProbability * 0.35)).coerceIn(0.0, 1.0),
            confidence = if (burstLevel == BurstLevel.LETHAL) 1.0 else confidence,
            burstLevel = burstLevel,
            predictedDamage = if (burstOverride) max(predictedDamage, snapshot.player.effectiveHealth + config.safetyMargin + 1.0) else predictedDamage,
            healthVelocity = history.velocity,
            lethalProbability = if (burstLevel == BurstLevel.LETHAL) 1.0 else lethalProbability,
            primaryThreatSource = source,
            shouldTriggerSetHome = shouldTrigger
        )
    }

    private fun pressure(value: Double, limit: Double): Double = if (limit <= 0.0) 1.0 else (value / limit).coerceIn(0.0, 1.0)
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
    val shouldTriggerSetHome: Boolean
) {
    fun toThreat(snapshot: WorldSnapshot): Threat = Threat(
        kind = if (burstLevel.ordinal >= BurstLevel.MEDIUM.ordinal) ThreatKind.BURST_DAMAGE else ThreatKind.COMBINED,
        damage = max(predictedDamage, snapshot.player.effectiveHealth + GrimSaverConfig.safetyMargin + 1.0),
        health = snapshot.player.effectiveHealth,
        source = primaryThreatSource,
        reason = "RiskEngine ${burstLevel.name.lowercase()} risk confidence=${(confidence * 100.0).toInt()}% velocity=${"%.2f".format(healthVelocity)} hp/s predicted=${"%.1f".format(predictedDamage)}",
        confidence = confidence,
        position = snapshot.player.position
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
    val source: String
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
        val confidence = (baseProjectileConfidence(projectile) + timing + (prediction.damage / max(1.0, snapshot.player.effectiveHealth)) * 0.12).coerceIn(0.0, 1.0)
        ProjectileRisk(impact.ticksToImpact, prediction.damage, confidence, prediction.source)
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
            "arrow" in type || "trident" in type -> ProjectileInfo(0.05, 0.5, 0.99)
            "potion" in type -> ProjectileInfo(0.05, 0.25, 0.99)
            "firework" in type -> ProjectileInfo(0.0, 0.25, 1.0)
            "fireball" in type || "wind_charge" in type -> ProjectileInfo(0.0, 1.0, 1.0)
            else -> ProjectileInfo(config.moddedProjectileGravity, config.moddedProjectileHitboxRadius, config.moddedProjectileDrag)
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
    val reason: String
) {
    fun weightedDamagePressure(effectiveHealth: Double): Double = predictedDamage / max(1.0, effectiveHealth) + targetingScore * 0.25 + enchantmentRiskScore * 0.1
    companion object {
        fun none() = CombatRisk(0.0, 0.0, 0.0, "none", "No threat")
    }
}

class EntityCombatAnalyzer(private val config: GrimSaverConfig = GrimSaverConfig) {
    fun analyze(attacker: LivingSnapshot, snapshot: WorldSnapshot): CombatRisk? {
        if (attacker.isPlayer && !config.pvpThreats) return null
        if (!attacker.isPlayer && !config.mobThreats) return null
        val intelligence = intelligence(attacker, snapshot)
        if (!intelligence.isAggressive && intelligence.attackRangePressure <= 0.0 && !intelligence.fallingAttack) return null
        val prediction = meleeDamage(attacker, snapshot.player, intelligence)
        return CombatRisk(prediction.damage, intelligence.targetingScore, enchantmentRisk(attacker.mainHand), prediction.source, prediction.reason)
    }

    fun projectileDamage(projectile: ProjectileSnapshot, player: PlayerSnapshot): DamagePrediction = DamagePredictor.projectile(projectile, player)

    fun fallRisk(player: PlayerSnapshot): CombatRisk {
        val prediction = fallDamage(player)
        return CombatRisk(prediction.damage, if (prediction.damage > 0.0) 0.55 else 0.0, 0.0, prediction.source, prediction.reason)
    }

    fun meleeDamage(attacker: LivingSnapshot, player: PlayerSnapshot, intelligence: EntityIntelligence = EntityIntelligence.neutral()): DamagePrediction {
        val stack = attacker.mainHand
        var raw = max(attacker.attackDamage, itemAttackDamage(stack))
        raw += stack.level(Enchantments.SHARPNESS).let { if (it > 0) 0.5 * it + 0.5 else 0.0 }
        raw += max(stack.level(Enchantments.SMITE), stack.level(Enchantments.BANE_OF_ARTHROPODS)) * 2.5
        raw += stack.level(Enchantments.FIRE_ASPECT) * 2.0
        raw += customEnchantmentRisk(stack) * 1.5
        if (intelligence.fallingAttack) raw += maceFallBonus(attacker, player, raw)
        val reduced = reduceWithArmorAndEnchantments(raw, player, DamageChannel.MELEE)
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
