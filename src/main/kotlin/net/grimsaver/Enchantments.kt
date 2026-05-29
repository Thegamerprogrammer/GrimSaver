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
        return EnchantmentHelper.getItemEnchantmentLevel(holder, this)
    }

    fun ItemStack?.hasComponentNbt(): Boolean {
        if (this == null || this.isEmpty) return false
        return this[DataComponents.CUSTOM_DATA] != null || this[DataComponents.ENCHANTMENTS] != null ||
            this[DataComponents.STORED_ENCHANTMENTS] != null || this[DataComponents.POTION_CONTENTS] != null ||
            this[DataComponents.FIREWORKS] != null
    }

    fun ItemStack?.describeCombatEnchantments(): String {
        if (this == null || this.isEmpty) return ""
        val parts = mutableListOf<String>()
        addIfPresent(parts, "Power", level(Enchantments.POWER))
        addIfPresent(parts, "Punch", level(Enchantments.PUNCH))
        addIfPresent(parts, "Flame", level(Enchantments.FLAME))
        addIfPresent(parts, "Multishot", level(Enchantments.MULTISHOT))
        addIfPresent(parts, "Piercing", level(Enchantments.PIERCING))
        addIfPresent(parts, "Sharpness", level(Enchantments.SHARPNESS))
        addIfPresent(parts, "Smite", level(Enchantments.SMITE))
        addIfPresent(parts, "Bane", level(Enchantments.BANE_OF_ARTHROPODS))
        addIfPresent(parts, "Fire Aspect", level(Enchantments.FIRE_ASPECT))
        addIfPresent(parts, "Knockback", level(Enchantments.KNOCKBACK))
        addIfPresent(parts, "Impaling", level(Enchantments.IMPALING))
        return parts.joinToString(" ")
    }

    fun ItemStack?.displayNameForReason(): String {
        if (this == null || this.isEmpty) return "unknown item"
        val enchants = describeCombatEnchantments()
        val itemName = hoverName.string.lowercase()
        return if (enchants.isBlank()) itemName else "$enchants $itemName"
    }

    private fun addIfPresent(parts: MutableList<String>, label: String, level: Int) {
        if (level > 0) parts += "$label ${roman(level)}"
    }

    private fun roman(level: Int): String = when (level) {
        1 -> "I"
        2 -> "II"
        3 -> "III"
        4 -> "IV"
        5 -> "V"
        else -> level.toString()
    }

    private fun ResourceKey<Enchantment>.holder(): Holder<Enchantment>? =
        MinecraftAccess.level?.registryAccess()
            ?.lookup(Registries.ENCHANTMENT)
            ?.getOrNull()
            ?.get(this)
            ?.getOrNull()
}
