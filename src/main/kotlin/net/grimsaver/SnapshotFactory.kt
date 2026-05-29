package net.grimsaver

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.ItemStack

object SnapshotFactory {
    fun capture(client: Minecraft, level: ClientLevel, player: LocalPlayer): WorldSnapshot {
        val projectiles = level.entitiesForRendering().asSequence()
            .filterIsInstance<Projectile>()
            .filter { it.isAlive }
            .map { projectile ->
                val owner = projectile.owner
                val arrow = projectile as? AbstractArrow
                ProjectileSnapshot(
                    id = projectile.id,
                    typeName = projectile.type.descriptionId,
                    position = projectile.position(),
                    velocity = projectile.deltaMovement,
                    boundingBox = projectile.boundingBox,
                    projectileStack = projectile.itemStackSnapshot(arrow),
                    shooterWeapon = arrow?.weaponItem?.copy() ?: (owner as? LivingEntity)?.mainHandItem?.copy(),
                    ownerName = owner?.displayName?.string,
                    critical = arrow?.isCritArrow ?: false,
                    onFire = projectile.isOnFire,
                    inGround = arrow?.isNoPhysics ?: projectile.noPhysics
                )
            }.toList()

        val livingEntities = level.entitiesForRendering().asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it !== player && it.isAlive && !it.isSpectator }
            .map { entity ->
                LivingSnapshot(
                    id = entity.id,
                    typeName = entity.type.descriptionId,
                    name = entity.displayName.string,
                    position = entity.position(),
                    velocity = entity.deltaMovement,
                    boundingBox = entity.boundingBox,
                    mainHand = entity.mainHandItem.copy(),
                    armorStacks = armorCopies(entity),
                    attackDamage = entity.getAttributeValue(Attributes.ATTACK_DAMAGE),
                    isPlayer = entity is Player,
                    isMob = entity is Mob,
                    targetId = (entity as? Mob)?.target?.id,
                    attackRange = entity.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE).coerceAtLeast(3.0)
                )
            }.toList()

        return WorldSnapshot(
            serverKey = client.currentServer?.ip ?: "singleplayer_${client.singleplayerServer?.worldData?.levelName ?: "local"}",
            dimension = level.dimension().toString(),
            playerId = player.id,
            player = PlayerSnapshot(
                position = player.position(),
                velocity = player.deltaMovement,
                boundingBox = player.boundingBox,
                health = player.health.toDouble(),
                absorption = player.absorptionAmount.toDouble(),
                armor = player.armorValue.toDouble(),
                armorToughness = player.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                armorStacks = armorCopies(player),
                fallDistance = player.fallDistance.toFloat(),
                safeFallDistance = player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                fallDamageMultiplier = player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER),
                onGround = player.onGround()
            ),
            projectiles = projectiles,
            livingEntities = livingEntities
        )
    }

    private fun Projectile.itemStackSnapshot(arrow: AbstractArrow?): ItemStack = when {
        arrow != null -> arrow.pickupItemStackOrigin.copy()
        this is ItemSupplier -> item.copy()
        else -> ItemStack.EMPTY
    }

    private fun armorCopies(entity: LivingEntity) = listOf(
        entity.getItemBySlot(EquipmentSlot.FEET).copy(),
        entity.getItemBySlot(EquipmentSlot.LEGS).copy(),
        entity.getItemBySlot(EquipmentSlot.CHEST).copy(),
        entity.getItemBySlot(EquipmentSlot.HEAD).copy()
    )
}
