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
    COMBINED("combined", "Combined Damage")
}

data class Threat(
    val kind: ThreatKind,
    val damage: Double,
    val health: Double,
    val source: String,
    val reason: String,
    val ticksUntilImpact: Int = 0,
    val position: Vec3
) {
    val cooldownKey: String = "${kind.id}:$source"
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
    val source: String
)

data class PlayerSnapshot(
    val position: Vec3,
    val velocity: Vec3,
    val boundingBox: AABB,
    val health: Double,
    val absorption: Double,
    val armor: Double,
    val armorToughness: Double,
    val armorStacks: List<ItemStack>,
    val fallDistance: Float,
    val safeFallDistance: Double,
    val fallDamageMultiplier: Double,
    val onGround: Boolean
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
    val inGround: Boolean
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
    val isPlayer: Boolean,
    val isMob: Boolean,
    val targetId: Int?,
    val attackRange: Double
)

data class WorldSnapshot(
    val serverKey: String,
    val dimension: String,
    val playerId: Int,
    val player: PlayerSnapshot,
    val projectiles: List<ProjectileSnapshot>,
    val livingEntities: List<LivingSnapshot>,
    val capturedAtMillis: Long = System.currentTimeMillis()
)
