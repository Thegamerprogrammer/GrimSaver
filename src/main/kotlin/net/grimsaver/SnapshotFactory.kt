package net.grimsaver

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack
import java.util.ArrayList

object SnapshotFactory {
    fun capture(client: Minecraft, level: ClientLevel, player: LocalPlayer): WorldSnapshot {
        val playerPosition = player.position()
        val maxDistanceSqr = GrimSaverConfig.scanRadius * GrimSaverConfig.scanRadius
        val projectiles = ArrayList<ProjectileSnapshot>(16)
        val livingEntities = ArrayList<LivingSnapshot>(32)

        val renderEntities = runCatching { level.entitiesForRendering() }.getOrElse { throwable ->
            warnGrimSaverFailure("entitiesForRendering", "Unable to enumerate render entities for GrimSaver snapshot", throwable)
            emptyList<Entity>()
        }

        for (entity in renderEntities) {
            if (!entity.withinSnapshotRadius(playerPosition, maxDistanceSqr)) continue
            when (entity) {
                is Projectile -> {
                    if (!GrimSaverConfig.projectileThreats || !entity.safeAlive()) continue
                    entity.snapshotOrNull()?.let(projectiles::add)
                }
                is LivingEntity -> {
                    if (entity === player || !entity.safeAlive() || entity.safeSpectator()) continue
                    if (entity is Player && !GrimSaverConfig.pvpThreats) continue
                    if (entity !is Player && !GrimSaverConfig.mobThreats) continue
                    entity.snapshotOrNull(player.id)?.let(livingEntities::add)
                }
            }
        }

        debugGrimSaver("Captured GrimSaver snapshot: {} projectiles, {} living entities", projectiles.size, livingEntities.size)

        return WorldSnapshot(
            serverKey = client.currentServer?.ip ?: "singleplayer_${client.singleplayerServer?.worldData?.levelName ?: "local"}",
            dimension = level.dimension().toString(),
            playerId = player.id,
            player = PlayerSnapshot(
                position = playerPosition,
                velocity = player.deltaMovement,
                boundingBox = player.boundingBox,
                health = player.health.toDouble(),
                absorption = player.absorptionAmount.toDouble(),
                armor = player.safeAttribute(Attributes.ARMOR),
                armorToughness = player.safeAttribute(Attributes.ARMOR_TOUGHNESS),
                maxHealth = player.safeAttribute(Attributes.MAX_HEALTH, 20.0),
                armorStacks = armorCopies(player),
                fallDistance = player.fallDistance.toFloat(),
                safeFallDistance = player.safeAttribute(Attributes.SAFE_FALL_DISTANCE, 3.0),
                fallDamageMultiplier = player.safeAttribute(Attributes.FALL_DAMAGE_MULTIPLIER, 1.0),
                onGround = player.onGround()
            ),
            projectiles = projectiles,
            livingEntities = livingEntities
        )
    }

    private fun Entity.withinSnapshotRadius(playerPosition: net.minecraft.world.phys.Vec3, maxDistanceSqr: Double): Boolean = runCatching {
        position().distanceToSqr(playerPosition) <= maxDistanceSqr
    }.getOrDefault(false)

    private fun Entity.safeAlive(): Boolean = runCatching { isAlive }.getOrDefault(false)

    private fun Entity.safeSpectator(): Boolean = runCatching { isSpectator }.getOrDefault(false)

    private fun Projectile.snapshotOrNull(): ProjectileSnapshot? = runCatching {
        val owner = owner
        val arrow = this as? AbstractArrow
        ProjectileSnapshot(
            id = id,
            typeName = type.descriptionId,
            position = position(),
            velocity = deltaMovement,
            boundingBox = boundingBox,
            projectileStack = itemStackSnapshot(arrow),
            shooterWeapon = arrow?.weaponItem?.copy() ?: (owner as? LivingEntity)?.mainHandItem?.copy(),
            ownerName = owner?.displayName?.string,
            critical = arrow?.isCritArrow ?: false,
            onFire = isOnFire,
            inGround = arrow?.isNoPhysics ?: noPhysics
        )
    }.getOrElse { throwable ->
        warnEntitySnapshotFailure(safeEntityType(), id, "projectile", throwable)
        null
    }

    private fun LivingEntity.snapshotOrNull(playerId: Int): LivingSnapshot? = runCatching {
        val mob = this as? Mob
        LivingSnapshot(
            id = id,
            typeName = type.descriptionId,
            name = displayName.string,
            position = position(),
            velocity = deltaMovement,
            boundingBox = boundingBox,
            mainHand = runCatching { mainHandItem.copy() }.getOrDefault(ItemStack.EMPTY),
            armorStacks = armorCopies(this),
            attackDamage = safeAttribute(Attributes.ATTACK_DAMAGE),
            attackSpeed = safeAttribute(Attributes.ATTACK_SPEED, 4.0),
            movementSpeed = safeAttribute(Attributes.MOVEMENT_SPEED),
            followRange = safeAttribute(Attributes.FOLLOW_RANGE),
            knockbackResistance = safeAttribute(Attributes.KNOCKBACK_RESISTANCE),
            maxHealth = safeAttribute(Attributes.MAX_HEALTH, 20.0),
            isPlayer = this is Player,
            isMob = mob != null,
            targetId = runCatching { mob?.target?.id }.getOrNull()?.takeIf { it == playerId },
            attackRange = safeAttribute(Attributes.ENTITY_INTERACTION_RANGE, 3.0).coerceAtLeast(3.0)
        )
    }.getOrElse { throwable ->
        warnEntitySnapshotFailure(safeEntityType(), id, "living", throwable)
        null
    }

    private fun Projectile.itemStackSnapshot(arrow: AbstractArrow?): ItemStack = runCatching {
        when {
            arrow != null -> arrow.pickupItemStackOrigin.copy()
            this is ItemSupplier -> item.copy()
            else -> ItemStack.EMPTY
        }
    }.getOrDefault(ItemStack.EMPTY)

    private fun armorCopies(entity: LivingEntity): List<ItemStack> = listOf(
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    ).map { slot -> runCatching { entity.getItemBySlot(slot).copy() }.getOrDefault(ItemStack.EMPTY) }

    private fun Entity.safeEntityType(): String = runCatching { type.descriptionId }.getOrDefault(javaClass.name)
}
