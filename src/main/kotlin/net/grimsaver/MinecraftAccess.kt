package net.grimsaver

import net.minecraft.client.Minecraft

object MinecraftAccess {
    val client: Minecraft get() = Minecraft.getInstance()
    val level get() = client.level
}
