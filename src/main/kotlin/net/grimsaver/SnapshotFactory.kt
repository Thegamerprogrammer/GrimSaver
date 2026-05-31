package net.grimsaver

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
                    if (!entity.safeAlive()) continue
                    entity.snapshotOrNull()?.let(projectiles::add)
                }
                is LivingEntity -> {
                    if (entity === player || !entity.safeAlive() || entity.safeSpectator()) continue
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
                onGround = player.onGround(),
                activeEffects = activeEffectSnapshots(player),
                fireImmune = runCatching { player.fireImmune() }.getOrDefault(false),
                regenerationPerSecond = regenerationRate(activeEffectSnapshots(player)),
                inventory = inventorySnapshot(player)
            ),
            projectiles = projectiles,
            livingEntities = livingEntities,
            terrain = terrainSnapshot(level, playerPosition)
        )
    }

    private fun inventorySnapshot(player: LocalPlayer): InventorySnapshot {
        val main = runCatching { player.mainHandItem.copy() }.getOrDefault(ItemStack.EMPTY)
        val offhand = runCatching { player.offhandItem.copy() }.getOrDefault(ItemStack.EMPTY)
        val carried = (0 until runCatching { player.inventory.containerSize }.getOrDefault(0)).mapNotNull { slot ->
            runCatching { player.inventory.getItem(slot).copy() }.getOrNull()
        }
        val placeable = carried.sumOf { stack ->
            if (!stack.isEmpty && stack.item is BlockItem) stack.count else 0
        }
        return InventorySnapshot(
            hasOffhandTotem = offhand.item == Items.TOTEM_OF_UNDYING,
            hasMainHandTotem = main.item == Items.TOTEM_OF_UNDYING,
            placeableBlockCount = placeable
        )
    }

    private fun terrainSnapshot(level: ClientLevel, playerPosition: net.minecraft.world.phys.Vec3): TerrainSnapshot {
        val origin = BlockPos.containing(playerPosition)
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        var openRoutes = 0
        var blocked = 0
        var losBreaks = 0
        var water = false
        var lava = false
        var climbable = false
        var corridorLike = false
        var hazards = 0
        var sampled = 0

        for ((dx, dz) in directions) {
            var clearRun = 0
            var sawWall = false
            for (step in 1..8) {
                val feet = origin.offset(dx * step, 0, dz * step)
                val head = feet.above()
                sampled++
                val feetState = runCatching { level.getBlockState(feet) }.getOrNull() ?: continue
                val headState = runCatching { level.getBlockState(head) }.getOrNull() ?: continue
                val belowState = runCatching { level.getBlockState(feet.below()) }.getOrNull()
                val passable = (feetState.isAir || !feetState.fluidState.isEmpty) && (headState.isAir || !headState.fluidState.isEmpty)
                if (passable) clearRun++ else sawWall = true
                water = water || feetState.fluidState.isSource && feetState.fluidState.type.toString().contains("water", true)
                lava = lava || feetState.fluidState.type.toString().contains("lava", true)
                if (lava) hazards++
                if (belowState != null && !belowState.isAir && step <= 3) climbable = true
            }
            if (clearRun >= 5) openRoutes++ else blocked++
            if (sawWall) losBreaks++
        }
        corridorLike = blocked >= 5 && openRoutes in 1..3
        return TerrainSnapshot(
            openEscapeDirections = openRoutes,
            blockedDirections = blocked,
            waterNearby = water,
            lavaNearby = lava,
            climbableTerrainNearby = climbable,
            doorwayOrCorridorNearby = corridorLike,
            lineOfSightBreaksNearby = losBreaks,
            hazardDensity = if (sampled == 0) 0.0 else hazards.toDouble() / sampled.toDouble()
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
            inGround = arrow?.isNoPhysics ?: noPhysics,
            ownerId = owner?.id,
            ageTicks = runCatching { tickCount }.getOrDefault(0),
            pickupStatus = arrow?.reflectInt("pickup", "pickupStatus") ?: -1,
            pierceLevel = arrow?.reflectInt("pierceLevel") ?: 0,
            potionEffects = potionEffectSnapshots(itemStackSnapshot(arrow)),
            gravity = projectileGravity(),
            baseDamage = arrow?.reflectDouble("baseDamage"),
            dataConfidence = if (arrow?.reflectDouble("baseDamage") != null) 0.92 else 0.68
        )
    }.getOrElse { throwable ->
        warnEntitySnapshotFailure(safeEntityType(), id, "projectile", throwable)
        null
    }

    private fun LivingEntity.snapshotOrNull(playerId: Int): LivingSnapshot? = runCatching {
        val mob = this as? Mob
        val creeper = this as? Creeper
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
            isEnemy = this is Enemy,
            isCreeper = creeper != null,
            creeperSwelling = creeper?.isActuallySwelling() ?: false,
            targetId = runCatching { mob?.target?.id }.getOrNull()?.takeIf { it == playerId },
            attackRange = safeAttribute(Attributes.ENTITY_INTERACTION_RANGE, 3.0).coerceAtLeast(3.0),
            attackCooldown = (this as? Player)?.let { runCatching { it.getAttackStrengthScale(0.0f).toDouble() }.getOrDefault(1.0) } ?: 1.0,
            isSprinting = runCatching { isSprinting }.getOrDefault(false),
            fallDistance = runCatching { fallDistance.toFloat() }.getOrDefault(0.0f),
            isOnGround = runCatching { onGround() }.getOrDefault(true),
            activeEffects = activeEffectSnapshots(this)
        )
    }.getOrElse { throwable ->
        warnEntitySnapshotFailure(safeEntityType(), id, "living", throwable)
        null
    }

    private fun Creeper.isActuallySwelling(): Boolean = runCatching {
        isIgnited || swellDir > 0
    }.getOrDefault(false)

    private fun Projectile.itemStackSnapshot(arrow: AbstractArrow?): ItemStack = runCatching {
        when {
            arrow != null -> arrow.pickupItemStackOrigin.copy()
            this is ItemSupplier -> item.copy()
            else -> ItemStack.EMPTY
        }
    }.getOrDefault(ItemStack.EMPTY)


    private fun activeEffectSnapshots(entity: LivingEntity): List<ActiveEffectSnapshot> = runCatching {
        entity.activeEffects.map { effect ->
            ActiveEffectSnapshot(
                id = runCatching { effect.effect.value().descriptionId }.getOrElse { effect.effect.toString() },
                amplifier = effect.amplifier,
                durationTicks = effect.duration,
                ambient = runCatching { effect.isAmbient }.getOrDefault(false),
                visible = runCatching { effect.isVisible }.getOrDefault(true)
            )
        }
    }.getOrDefault(emptyList())

    private fun potionEffectSnapshots(stack: ItemStack): List<ActiveEffectSnapshot> = runCatching {
        stack[DataComponents.POTION_CONTENTS]?.allEffects?.map { effect ->
            ActiveEffectSnapshot(
                id = runCatching { effect.effect.value().descriptionId }.getOrElse { effect.effect.toString() },
                amplifier = effect.amplifier,
                durationTicks = effect.duration,
                ambient = runCatching { effect.isAmbient }.getOrDefault(false),
                visible = runCatching { effect.isVisible }.getOrDefault(true)
            )
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun regenerationRate(effects: List<ActiveEffectSnapshot>): Double {
        val regeneration = effects.firstOrNull { it.matches("regeneration") } ?: return 0.0
        val intervalTicks = (50 shr regeneration.amplifier).coerceAtLeast(1)
        return 1.0 / (intervalTicks * 0.05)
    }

    private fun Projectile.projectileGravity(): Double {
        val type = type.descriptionId.lowercase()
        return when {
            "arrow" in type || "trident" in type || "potion" in type -> 0.05
            "firework" in type || "fireball" in type || "wind_charge" in type -> 0.0
            else -> GrimSaverConfig.moddedProjectileGravity
        }
    }

    private fun Any.reflectInt(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        runCatching {
            javaClass.findField(name)?.let { field ->
                field.isAccessible = true
                when (val value = field.get(this)) {
                    is Number -> value.toInt()
                    is Enum<*> -> value.ordinal
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun Any.reflectDouble(vararg names: String): Double? = names.firstNotNullOfOrNull { name ->
        runCatching {
            javaClass.findField(name)?.let { field ->
                field.isAccessible = true
                (field.get(this) as? Number)?.toDouble()
            }
        }.getOrNull()
    }

    private fun Class<*>.findField(name: String): java.lang.reflect.Field? {
        var current: Class<*>? = this
        while (current != null) {
            current.declaredFields.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun armorCopies(entity: LivingEntity): List<ItemStack> = listOf(
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    ).map { slot -> runCatching { entity.getItemBySlot(slot).copy() }.getOrDefault(ItemStack.EMPTY) }

    private fun Entity.safeEntityType(): String = runCatching { type.descriptionId }.getOrDefault(javaClass.name)
}
