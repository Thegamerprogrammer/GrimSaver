package net.grimsaver

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import kotlin.jvm.optionals.getOrNull

object EnchantmentLevels {
    fun ItemStack?.level(enchantment: ResourceKey<Enchantment>): Int {
        if (this == null || this.isEmpty) return 0
        val holder = enchantment.holder() ?: return 0
        return runCatching { EnchantmentHelper.getItemEnchantmentLevel(holder, this) }.getOrDefault(0)
    }

    fun ItemStack?.hasComponentNbt(): Boolean = hasCombatRelevantComponents()

    fun ItemStack?.hasCombatRelevantComponents(): Boolean {
        if (this == null || this.isEmpty) return false
        return runCatching {
            this[DataComponents.CUSTOM_DATA] != null || this[DataComponents.ATTRIBUTE_MODIFIERS] != null ||
                this[DataComponents.ENCHANTMENTS] != null || this[DataComponents.STORED_ENCHANTMENTS] != null ||
                this[DataComponents.POTION_CONTENTS] != null || this[DataComponents.FIREWORKS] != null
        }.getOrDefault(false)
    }

    fun ItemStack?.describeCombatEnchantments(): String {
        if (this == null || this.isEmpty) return ""
        return COMBAT_ENCHANTMENTS.mapNotNull { (label, enchantment) ->
            level(enchantment).takeIf { it > 0 }?.let { "$label ${roman(it)}" }
        }.joinToString(" ")
    }

    fun ItemStack?.displayNameForReason(): String {
        if (this == null || this.isEmpty) return "unknown item"
        val enchants = describeCombatEnchantments()
        val itemName = runCatching { hoverName.string.lowercase() }.getOrDefault("unknown item")
        return if (enchants.isBlank()) itemName else "$enchants $itemName"
    }

    private fun roman(level: Int): String = when (level) {
        1 -> "I"
        2 -> "II"
        3 -> "III"
        4 -> "IV"
        5 -> "V"
        6 -> "VI"
        7 -> "VII"
        8 -> "VIII"
        9 -> "IX"
        10 -> "X"
        else -> level.toString()
    }

    private val COMBAT_ENCHANTMENTS = listOf(
        "Power" to Enchantments.POWER,
        "Punch" to Enchantments.PUNCH,
        "Flame" to Enchantments.FLAME,
        "Multishot" to Enchantments.MULTISHOT,
        "Piercing" to Enchantments.PIERCING,
        "Sharpness" to Enchantments.SHARPNESS,
        "Smite" to Enchantments.SMITE,
        "Bane" to Enchantments.BANE_OF_ARTHROPODS,
        "Fire Aspect" to Enchantments.FIRE_ASPECT,
        "Knockback" to Enchantments.KNOCKBACK,
        "Impaling" to Enchantments.IMPALING
    )

    private fun ResourceKey<Enchantment>.holder(): Holder<Enchantment>? =
        MinecraftAccess.level?.registryAccess()
            ?.lookup(Registries.ENCHANTMENT)
            ?.getOrNull()
            ?.get(this)
            ?.getOrNull()
}
