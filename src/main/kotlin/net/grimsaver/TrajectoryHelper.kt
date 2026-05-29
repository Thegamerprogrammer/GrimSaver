package net.grimsaver

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object TrajectoryHelper {
    /**
     * Silent adaptation of LiquidBounce nextgen's ModuleAutoDodge + SimulatedArrow loop:
     * advance projectile position, apply drag/gravity, and clip each segment against an expanded player hitbox.
     * LiquidBounce uses this for movement evasion; GrimSaver reuses the prediction shape only and never renders it.
     */
    fun firstPlayerIntersection(projectile: ProjectileSnapshot, playerBox: AABB, maxTicks: Int): Impact? {
        if (projectile.inGround || projectile.velocity.lengthSqr() < 0.0001) return null
        var pos = projectile.position
        var velocity = projectile.velocity
        val info = projectile.info()
        val expandedPlayer = playerBox.inflate(0.35 + info.hitboxRadius)

        repeat(maxTicks) { tick ->
            val previous = pos
            val next = pos.add(velocity)
            expandedPlayer.clip(previous, next).orElse(null)?.let { hit ->
                return Impact(tick + 1, hit, previous, velocity)
            }
            pos = next
            velocity = velocity.scale(info.drag).subtract(0.0, info.gravity, 0.0)
        }
        return null
    }

    private fun ProjectileSnapshot.info(): ProjectileInfo {
        val type = typeName.lowercase()
        return when {
            "arrow" in type || "trident" in type -> ProjectileInfo(gravity = 0.05, hitboxRadius = 0.5, drag = 0.99)
            "potion" in type -> ProjectileInfo(gravity = 0.05, hitboxRadius = 0.25, drag = 0.99)
            "firework" in type -> ProjectileInfo(gravity = 0.0, hitboxRadius = 0.25, drag = 1.0)
            "fireball" in type || "wind_charge" in type -> ProjectileInfo(gravity = 0.0, hitboxRadius = 1.0, drag = 1.0)
            else -> ProjectileInfo(gravity = 0.03, hitboxRadius = 0.25, drag = 0.99)
        }
    }

    data class Impact(val ticks: Int, val hit: Vec3, val previousProjectilePosition: Vec3, val velocity: Vec3)
    private data class ProjectileInfo(val gravity: Double, val hitboxRadius: Double, val drag: Double)
}
