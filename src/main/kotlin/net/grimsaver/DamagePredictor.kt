package net.grimsaver

import net.grimsaver.EnchantmentLevels.describeCombatEnchantments
import net.grimsaver.EnchantmentLevels.displayNameForReason
import net.grimsaver.EnchantmentLevels.hasComponentNbt
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object DamagePredictor {
    fun projectile(projectile: ProjectileSnapshot, player: PlayerSnapshot): DamagePrediction {
        val type = projectile.typeName.lowercase()
        val shooterWeapon = projectile.shooterWeapon
        val projectileStack = projectile.projectileStack
        var raw = when {
            "arrow" in type -> arrowDamage(projectile, shooterWeapon)
            "trident" in type -> 8.0 + projectile.velocity.length().coerceAtMost(3.5) + shooterWeapon.level(Enchantments.IMPALING) * 1.25
            "potion" in type -> potionDamage(projectileStack)
            "firework" in type -> fireworkDamage(projectileStack, shooterWeapon)
            "wind_charge" in type || "fireball" in type -> 6.0
            else -> max(1.0, projectile.velocity.length() * 2.0)
        }

        if (projectile.onFire || shooterWeapon.level(Enchantments.FLAME) > 0) raw += 3.0
        val reduced = reduceWithArmorAndEnchantments(raw, player, projectile = true, explosion = "firework" in type || "fireball" in type)
        return DamagePrediction(
            damage = reduced,
            reason = projectileReason(projectile, shooterWeapon, projectileStack),
            source = projectile.ownerName ?: projectile.typeName,
            confidence = projectile.dataConfidence
        )
    }

    fun melee(attacker: LivingSnapshot, player: PlayerSnapshot): DamagePrediction {
        val stack = attacker.mainHand
        var raw = max(attacker.attackDamage, itemAttackDamage(stack))
        raw *= (0.2 + attacker.attackCooldown.coerceIn(0.0, 1.0).let { it * it * 0.8 })
        raw += stack.level(Enchantments.SHARPNESS).let { if (it > 0) 0.5 * it + 0.5 else 0.0 }
        raw += max(stack.level(Enchantments.SMITE), stack.level(Enchantments.BANE_OF_ARTHROPODS)) * 2.5
        raw += strengthBonus(attacker)
        raw -= weaknessPenalty(attacker)
        if (!attacker.isOnGround && attacker.fallDistance > 0.0f && attacker.velocity.y < -0.08) raw *= 1.5
        if (attacker.isSprinting) raw += 1.0
        if (stack.item == Items.MACE) raw += maceSmashBonus(attacker)
        raw += stack.level(Enchantments.FIRE_ASPECT) * 2.0
        raw += attacker.armorStacks.sumOf { it.level(Enchantments.THORNS) * 0.5 }
        raw = raw.coerceAtLeast(0.0)

        val reduced = reduceWithArmorAndEnchantments(raw, player, projectile = false, explosion = false)
        val entityName = attacker.name.ifBlank { attacker.typeName }
        val source = if (attacker.isPlayer) entityName else attacker.typeName.substringAfterLast('.')
        val weapon = if (stack.isEmpty) "hands" else stack.displayNameForReason()
        return DamagePrediction(reduced, "${if (attacker.isPlayer) "Melee Player" else "Melee ${source.replaceFirstChar { it.titlecase() }}"} with $weapon", entityName, confidence = if (attacker.activeEffects.isNotEmpty()) 0.9 else 0.78)
    }

    fun fall(player: PlayerSnapshot): DamagePrediction {
        if (player.onGround || player.velocity.y >= -0.08) return DamagePrediction(0.0, "Fall Damage", "fall")
        val predictedDistance = player.fallDistance + (-player.velocity.y * 8.0) + GrimSaverConfig.fallDamageMargin
        val raw = max(0.0, ceil(predictedDistance - player.safeFallDistance) * player.fallDamageMultiplier)
        if (raw <= 0.0) return DamagePrediction(0.0, "Fall Damage", "fall")
        val reduced = reduceFall(raw, player)
        return DamagePrediction(reduced, "Fall Damage", "fall", confidence = 0.8)
    }

    private fun arrowDamage(projectile: ProjectileSnapshot, shooterWeapon: ItemStack?): Double {
        // Vanilla arrow damage starts from velocity * base damage. We do not have the server's exact baseDamage field
        // client-side, so this original predictor uses speed, critical state and full shooter/projectile components.
        val base = projectile.baseDamage ?: 2.0
        val speed = projectile.velocity.length().coerceIn(0.0, 3.5)
        var damage = ceil(speed * base).coerceAtLeast(base)
        val power = shooterWeapon.level(Enchantments.POWER)
        if (power > 0) damage += power * 0.5 + 0.5
        if (projectile.critical) damage *= 1.25
        damage += potionDamage(projectile.projectileStack, immediateOnly = true)
        return damage
    }

    private fun potionDamage(stack: ItemStack, immediateOnly: Boolean = false): Double {
        return runCatching {
            val contents = stack[DataComponents.POTION_CONTENTS] ?: return 0.0
            var damage = 0.0
            for (effect in contents.allEffects) {
                when {
                    effect.`is`(MobEffects.INSTANT_DAMAGE) -> damage += 6.0 * (effect.amplifier + 1)
                    effect.`is`(MobEffects.POISON) && !immediateOnly -> damage += min(8.0, (effect.duration / 25.0) * (effect.amplifier + 1))
                    effect.`is`(MobEffects.WITHER) && !immediateOnly -> damage += min(12.0, (effect.duration / 20.0) * (effect.amplifier + 1))
                }
            }
            damage
        }.getOrDefault(0.0)
    }

    private fun fireworkDamage(stack: ItemStack, shooterWeapon: ItemStack?): Double {
        val explosions = runCatching { stack[DataComponents.FIREWORKS]?.explosions()?.size ?: 1 }.getOrDefault(1)
        return 5.0 + explosions * 2.0 + shooterWeapon.level(Enchantments.MULTISHOT)
    }

    private fun itemAttackDamage(stack: ItemStack): Double {
        val componentDamage = runCatching {
            stack[DataComponents.ATTRIBUTE_MODIFIERS]?.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND)
        }.getOrNull()
        if (componentDamage != null && componentDamage > 1.0) return componentDamage
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
                else -> 2.0
            }
        }
    }


    private fun strengthBonus(attacker: LivingSnapshot): Double = attacker.activeEffects
        .firstOrNull { it.matches("strength") }
        ?.let { 3.0 * (it.amplifier + 1) }
        ?: 0.0

    private fun weaknessPenalty(attacker: LivingSnapshot): Double = attacker.activeEffects
        .firstOrNull { it.matches("weakness") }
        ?.let { 4.0 * (it.amplifier + 1) }
        ?: 0.0

    private fun maceSmashBonus(attacker: LivingSnapshot): Double {
        if (attacker.fallDistance <= 1.5f || attacker.velocity.y >= -0.08) return 0.0
        return attacker.fallDistance.toDouble() * 1.5 + (-attacker.velocity.y * 2.0)
    }

    private fun applyResistance(damage: Double, player: PlayerSnapshot): Double {
        val resistance = player.activeEffects.firstOrNull { it.matches("resistance") } ?: return damage
        return damage * (1.0 - ((resistance.amplifier + 1) * 0.2).coerceAtMost(0.8))
    }

    private fun reduceWithArmorAndEnchantments(raw: Double, player: PlayerSnapshot, projectile: Boolean, explosion: Boolean): Double {
        val afterArmor = vanillaArmorReduction(raw, player.armor, player.armorToughness)
        val epf = player.armorStacks.sumOf { armor ->
            armor.level(Enchantments.PROTECTION) +
                (if (projectile) armor.level(Enchantments.PROJECTILE_PROTECTION) * 2 else 0) +
                (if (explosion) armor.level(Enchantments.BLAST_PROTECTION) * 2 else 0)
        }.coerceIn(0, 20)
        return applyResistance(afterArmor * (1.0 - epf / 25.0), player)
    }

    private fun reduceFall(raw: Double, player: PlayerSnapshot): Double {
        val epf = player.armorStacks.sumOf { armor ->
            armor.level(Enchantments.PROTECTION) + armor.level(Enchantments.FEATHER_FALLING) * 3
        }.coerceIn(0, 20)
        return applyResistance(raw * (1.0 - epf / 25.0), player)
    }

    private fun vanillaArmorReduction(damage: Double, armor: Double, toughness: Double): Double {
        val armorFactor = min(20.0, max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0)))
        return damage * (1.0 - armorFactor / 25.0)
    }

    private fun projectileReason(projectile: ProjectileSnapshot, shooterWeapon: ItemStack?, projectileStack: ItemStack): String {
        val type = when {
            "arrow" in projectile.typeName.lowercase() -> "Lethal Arrow"
            "trident" in projectile.typeName.lowercase() -> "Lethal Trident"
            "potion" in projectile.typeName.lowercase() -> "Lethal Potion"
            "firework" in projectile.typeName.lowercase() -> "Lethal Firework"
            else -> "Lethal Projectile"
        }
        val weapon = shooterWeapon.displayNameForReason()
        val nbt = if (projectileStack.hasComponentNbt()) " + projectile NBT" else ""
        val enchants = shooterWeapon.describeCombatEnchantments()
        return if (shooterWeapon == null || shooterWeapon.isEmpty) "$type$nbt" else "$type from ${if (enchants.isBlank()) weapon else weapon}$nbt"
    }
}
