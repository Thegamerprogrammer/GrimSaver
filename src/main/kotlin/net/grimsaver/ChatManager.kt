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
            if (!isGrimSaverCommand(message)) return@AllowChat true
            Minecraft.getInstance().execute { handleGrimSaverCommand(Minecraft.getInstance(), message) }
            false
        })
        ClientSendMessageEvents.ALLOW_COMMAND.register(ClientSendMessageEvents.AllowCommand { command ->
            Minecraft.getInstance().execute { homeManager.observeHomeCommand(Minecraft.getInstance(), command) }
            true
        })
    }

    private fun handleGrimSaverCommand(client: Minecraft, message: String) {
        val args = message.trim().split(Regex("\\s+")).drop(1)
        if (args.isEmpty()) {
            showSavedHomes(client)
            sendHelp(client)
            return
        }
        when (args[0].lowercase()) {
            "risk" -> handleRisk(client, args.drop(1))
            "set" -> handleSet(client, args.drop(1))
            "home" -> handleHome(client, args.drop(1))
            "homes", "list" -> showSavedHomes(client)
            else -> sendHelp(client)
        }
    }

    private fun handleRisk(client: Minecraft, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "enable", "on", "true" -> { GrimSaverConfig.riskEngineEnabled = true; saveAndAck(client, "riskEngineEnabled", true) }
            "disable", "off", "false" -> { GrimSaverConfig.riskEngineEnabled = false; saveAndAck(client, "riskEngineEnabled", false) }
            "debug" -> {
                GrimSaverConfig.riskDebug = !GrimSaverConfig.riskDebug
                saveAndAck(client, "riskDebug", GrimSaverConfig.riskDebug)
            }
            else -> sendSystem(client, Component.literal("§6GrimSaver §7| Risk engine enabled=${GrimSaverConfig.riskEngineEnabled}, debug=${GrimSaverConfig.riskDebug}"), false)
        }
    }

    private fun handleSet(client: Minecraft, args: List<String>) {
        if (args.size < 2) {
            sendSystem(client, Component.literal("§cUsage: .grimsaver set <key> <value>"), false)
            return
        }
        val key = args[0].lowercase()
        val value = args[1]
        val ok = when (key) {
            "burstthreshold", "burstabsolutethreshold" -> value.toDoubleOrNull()?.let { GrimSaverConfig.burstAbsoluteThreshold = it.coerceIn(0.5, 40.0); true }
            "burstvelocity", "burstvelocitythreshold" -> value.toDoubleOrNull()?.let { GrimSaverConfig.burstVelocityThreshold = it.coerceIn(0.5, 120.0); true }
            "healthweight", "healthvelocityweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.healthVelocityWeight = it.coerceIn(0.0, 1.0); true }
            "burstweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.burstWeight = it.coerceIn(0.0, 1.0); true }
            "damageweight", "damagepredictionweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.damagePredictionWeight = it.coerceIn(0.0, 1.0); true }
            "targetingweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.targetingWeight = it.coerceIn(0.0, 1.0); true }
            "enchantmentweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.enchantmentWeight = it.coerceIn(0.0, 1.0); true }
            "trajectoryweight" -> value.toDoubleOrNull()?.let { GrimSaverConfig.trajectoryWeight = it.coerceIn(0.0, 1.0); true }
            "lethalconfidencethreshold" -> value.toDoubleOrNull()?.let { GrimSaverConfig.lethalConfidenceThreshold = it.coerceIn(0.5, 1.0); true }
            else -> null
        } ?: false
        if (ok) saveAndAck(client, key, value) else sendSystem(client, Component.literal("§cUnknown or invalid GrimSaver setting: $key"), false)
    }

    private fun handleHome(client: Minecraft, args: List<String>) {
        if (args.isEmpty()) {
            showSavedHomes(client)
            return
        }
        when (args[0].lowercase()) {
            "max" -> {
                val max = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 4)
                if (max == null) sendSystem(client, Component.literal("§cUsage: .grimsaver home max <1-4>"), false) else {
                    GrimSaverConfig.homeMaxSlots = max
                    saveAndAck(client, "homeMaxSlots", max)
                }
            }
            "automax", "usemax" -> {
                GrimSaverConfig.homeUseMaxSlot = args.getOrNull(1)?.toBooleanStrictOrNull() ?: !GrimSaverConfig.homeUseMaxSlot
                saveAndAck(client, "homeUseMaxSlot", GrimSaverConfig.homeUseMaxSlot)
            }
            "autodelete" -> {
                GrimSaverConfig.homeAutoDelete = args.getOrNull(1)?.toBooleanStrictOrNull() ?: !GrimSaverConfig.homeAutoDelete
                saveAndAck(client, "homeAutoDelete", GrimSaverConfig.homeAutoDelete)
            }
            "deletedelay" -> {
                val delay = args.getOrNull(1)?.toLongOrNull()?.coerceIn(0L, 60_000L)
                if (delay == null) sendSystem(client, Component.literal("§cUsage: .grimsaver home deleteDelay <millis>"), false) else {
                    GrimSaverConfig.homeDeleteDelayMillis = delay
                    saveAndAck(client, "homeDeleteDelayMillis", delay)
                }
            }
            else -> sendSystem(client, Component.literal("§cUsage: .grimsaver home max|autoDelete|deleteDelay|useMax ..."), false)
        }
    }

    private fun saveAndAck(client: Minecraft, key: String, value: Any) {
        GrimSaverConfig.save()
        sendSystem(client, Component.literal("§6GrimSaver §7| Set §e$key§7 = §f$value"), false)
    }

    private fun sendHelp(client: Minecraft) {
        sendSystem(client, Component.literal("§6GrimSaver §7| Commands: .grimsaver risk enable|debug, .grimsaver set burstThreshold 6.0, .grimsaver set healthWeight 0.5, .grimsaver home max 4, .grimsaver home autoDelete true, .grimsaver home deleteDelay 6000"), false)
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

    private fun isGrimSaverCommand(message: String): Boolean {
        val first = message.trim().substringBefore(' ')
        return first.equals(".grimsaver", ignoreCase = true) || first.equals(".gs", ignoreCase = true)
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
