package net.grimsaver

import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.Instant

enum class ThreatKind(val id: String, val displayName: String) {
    LETHAL_PROJECTILE("lethal_projectile", "Lethal Projectile"),
    LETHAL_ARROW("lethal_arrow", "Lethal Arrow"),
    LETHAL_TRIDENT("lethal_trident", "Lethal Trident"),
    LETHAL_POTION("lethal_potion", "Lethal Potion"),
    LETHAL_FIREWORK("lethal_firework", "Lethal Firework"),
    MELEE_PLAYER("melee_player", "Melee Player"),
    MELEE_MOB("melee_mob", "Melee Mob"),
    FALL_DAMAGE("fall_damage", "Fall Damage"),
    COMBINED("combined", "Combined Damage"),
    CRITICAL_HEALTH("critical_health", "Critical Health"),
    CREEPER_EMERGENCY("creeper_emergency", "Creeper Emergency"),
    SWARM_EMERGENCY("swarm_emergency", "Swarm Emergency"),
    DEATH_FAILSAFE("death_failsafe", "Death Failsafe"),
    CRITICAL_DAMAGE("critical_damage", "Critical Damage"),
    BURST_DAMAGE("burst_damage", "Burst Damage"),
    PASSIVE_ENTITY_DANGER("passive_entity_danger", "Passive Entity Danger")
}

data class Threat(
    val kind: ThreatKind,
    val damage: Double,
    val health: Double,
    val source: String,
    val reason: String,
    val confidence: Double,
    val ticksUntilImpact: Int = 0,
    val position: Vec3,
    val predictedDamage: Double = damage,
    val lethalProbability: Double = confidence,
    val sourceEntityId: Int? = null
) {
    val cooldownKey: String = "${kind.id}:${sourceEntityId ?: source}"
}

data class SavedHome(
    val name: String,
    val timestamp: Instant,
    val position: Vec3,
    val reason: String,
    val damage: Double,
    val threatKind: ThreatKind,
    val source: String
)

data class DamagePrediction(
    val damage: Double,
    val reason: String,
    val source: String,
    val confidence: Double = 0.75
)

data class ActiveEffectSnapshot(
    val id: String,
    val amplifier: Int,
    val durationTicks: Int,
    val ambient: Boolean = false,
    val visible: Boolean = true
) {
    fun matches(name: String): Boolean = id.contains(name, ignoreCase = true)
}

data class PlayerSnapshot(
    val position: Vec3,
    val velocity: Vec3,
    val boundingBox: AABB,
    val health: Double,
    val absorption: Double,
    val armor: Double,
    val armorToughness: Double,
    val maxHealth: Double,
    val armorStacks: List<ItemStack>,
    val fallDistance: Float,
    val safeFallDistance: Double,
    val fallDamageMultiplier: Double,
    val onGround: Boolean,
    val activeEffects: List<ActiveEffectSnapshot> = emptyList(),
    val fireImmune: Boolean = false,
    val regenerationPerSecond: Double = 0.0,
    val inventory: InventorySnapshot = InventorySnapshot()
) {
    val effectiveHealth: Double = health + absorption
}

data class ProjectileSnapshot(
    val id: Int,
    val typeName: String,
    val position: Vec3,
    val velocity: Vec3,
    val boundingBox: AABB,
    val projectileStack: ItemStack,
    val shooterWeapon: ItemStack?,
    val ownerName: String?,
    val critical: Boolean,
    val onFire: Boolean,
    val inGround: Boolean,
    val ownerId: Int? = null,
    val ageTicks: Int = 0,
    val pickupStatus: Int = -1,
    val pierceLevel: Int = 0,
    val potionEffects: List<ActiveEffectSnapshot> = emptyList(),
    val gravity: Double = 0.03,
    val baseDamage: Double? = null,
    val dataConfidence: Double = 0.6
)

data class LivingSnapshot(
    val id: Int,
    val typeName: String,
    val name: String,
    val position: Vec3,
    val velocity: Vec3,
    val boundingBox: AABB,
    val mainHand: ItemStack,
    val armorStacks: List<ItemStack>,
    val attackDamage: Double,
    val attackSpeed: Double,
    val movementSpeed: Double,
    val followRange: Double,
    val knockbackResistance: Double,
    val maxHealth: Double,
    val isPlayer: Boolean,
    val isMob: Boolean,
    val isEnemy: Boolean,
    val isCreeper: Boolean,
    val creeperSwelling: Boolean,
    val targetId: Int?,
    val attackRange: Double,
    val attackCooldown: Double = 1.0,
    val isSprinting: Boolean = false,
    val fallDistance: Float = 0.0f,
    val isOnGround: Boolean = true,
    val activeEffects: List<ActiveEffectSnapshot> = emptyList()
)

data class InventorySnapshot(
    val hasOffhandTotem: Boolean = false,
    val hasMainHandTotem: Boolean = false,
    val placeableBlockCount: Int = 0
) {
    val hasTotem: Boolean = hasOffhandTotem || hasMainHandTotem
    val canPlaceSurvivalBlocks: Boolean = placeableBlockCount > 0
}

data class TerrainSnapshot(
    val openEscapeDirections: Int = 0,
    val blockedDirections: Int = 0,
    val waterNearby: Boolean = false,
    val lavaNearby: Boolean = false,
    val climbableTerrainNearby: Boolean = false,
    val doorwayOrCorridorNearby: Boolean = false,
    val lineOfSightBreaksNearby: Int = 0,
    val hazardDensity: Double = 0.0
)

data class WorldSnapshot(
    val serverKey: String,
    val dimension: String,
    val playerId: Int,
    val player: PlayerSnapshot,
    val projectiles: List<ProjectileSnapshot>,
    val livingEntities: List<LivingSnapshot>,
    val terrain: TerrainSnapshot = TerrainSnapshot(),
    val capturedAtMillis: Long = System.currentTimeMillis()
)
