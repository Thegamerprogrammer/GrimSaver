package net.grimsaver

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatManager(private val homeManager: HomeManager) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(ClientSendMessageEvents.AllowChat { message ->
            if (!isCommand(message)) return@AllowChat true
            Minecraft.getInstance().execute { showSavedHomes(Minecraft.getInstance()) }
            false
        })
    }

    fun showSavedHomes(client: Minecraft) {
        val homes = homeManager.recentHomes(client, limit = 10)
        sendSystem(client, Component.literal("§6LastStand §7| Saved Homes:"), preserveScroll = false)
        if (homes.isEmpty()) {
            sendSystem(client, Component.literal("§7No saved homes yet."), preserveScroll = false)
            return
        }
        homes.forEach { sendSystem(client, homeLine(it), preserveScroll = false) }
    }

    fun announceSavedHome(client: Minecraft, savedHome: SavedHome) {
        sendSystem(
            client,
            Component.literal("§6LastStand §7| Saved ").append(clickableHome(savedHome.name)).append(" §7• §c${savedHome.reason}"),
            preserveScroll = true
        )
    }

    private fun homeLine(home: SavedHome): MutableComponent = Component.literal("§e ")
        .append(clickableHome(home.name))
        .append(" §7• §f${formatter.format(home.timestamp)} §7• §c${home.threatKind.displayName}")

    private fun clickableHome(name: String): MutableComponent = Component.literal(name).withStyle { style ->
        style.withColor(ChatFormatting.YELLOW)
            .withClickEvent(ClickEvent.RunCommand("/home $name"))
            .withUnderlined(true)
    }

    private fun sendSystem(client: Minecraft, component: Component, preserveScroll: Boolean) {
        val chat = client.gui.chat
        if (!preserveScroll) {
            chat.addClientSystemMessage(component)
            return
        }

        val scroll = readIntField(chat, "chatScrollbarPos")
        val newMessage = readBooleanField(chat, "newMessageSinceScroll")
        chat.addClientSystemMessage(component)
        if (scroll != null) writeIntField(chat, "chatScrollbarPos", scroll)
        if (newMessage != null) writeBooleanField(chat, "newMessageSinceScroll", newMessage)
    }

    private fun isCommand(message: String): Boolean {
        val trimmed = message.trim()
        return trimmed.equals(".grimsaver", ignoreCase = true) || trimmed.equals(".gs", ignoreCase = true)
    }

    private fun readIntField(chat: ChatComponent, name: String): Int? = field(chat, name, Int::class.javaPrimitiveType)?.getInt(chat)
    private fun writeIntField(chat: ChatComponent, name: String, value: Int) {
        field(chat, name, Int::class.javaPrimitiveType)?.setInt(chat, value)
    }

    private fun readBooleanField(chat: ChatComponent, name: String): Boolean? =
        field(chat, name, Boolean::class.javaPrimitiveType)?.getBoolean(chat)

    private fun writeBooleanField(chat: ChatComponent, name: String, value: Boolean) {
        field(chat, name, Boolean::class.javaPrimitiveType)?.setBoolean(chat, value)
    }

    private fun field(chat: ChatComponent, namedField: String, type: Class<*>?) = runCatching {
        val declaredFields = chat.javaClass.declaredFields
        (declaredFields.firstOrNull { it.name == namedField } ?: declaredFields.firstOrNull { field ->
            !java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == type
        })?.apply { isAccessible = true }
    }.getOrNull()
}
