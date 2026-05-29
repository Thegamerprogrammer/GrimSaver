package net.grimsaver

import net.minecraft.core.Holder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val entitySafetyLogger = LoggerFactory.getLogger("GrimSaver")
private val missingAttributeWarnings = ConcurrentHashMap.newKeySet<String>()
private val entityFailureWarnings = ConcurrentHashMap.newKeySet<String>()

fun LivingEntity.safeAttribute(attribute: Holder<Attribute>, default: Double = 0.0): Double {
    val attributeName = attribute.safeAttributeName()
    return try {
        val instance = getAttribute(attribute)
        if (instance == null) {
            warnOnce(
                missingAttributeWarnings,
                "${type.descriptionId}|$attributeName",
                "Entity type {} is missing attribute {}; using default {}",
                type.descriptionId,
                attributeName,
                default
            )
            default
        } else {
            instance.safeValue(this, attributeName, default)
        }
    } catch (throwable: Throwable) {
        warnOnce(
            missingAttributeWarnings,
            "${type.descriptionId}|$attributeName|${throwable.javaClass.name}",
            "Could not read attribute {} from entity type {}; using default {}",
            attributeName,
            type.descriptionId,
            default,
            throwable
        )
        default
    }
}

fun warnEntitySnapshotFailure(entityType: String, entityId: Int, context: String, throwable: Throwable) {
    warnOnce(
        entityFailureWarnings,
        "$context|$entityType|${throwable.javaClass.name}",
        "Skipping {} snapshot for entity type {} (id {}) after unexpected entity error",
        context,
        entityType,
        entityId,
        throwable
    )
}

fun warnGrimSaverFailure(key: String, message: String, throwable: Throwable) {
    warnOnce(entityFailureWarnings, "$key|${throwable.javaClass.name}", message, throwable)
}

fun debugGrimSaver(message: String, vararg args: Any?) {
    if (GrimSaverConfig.debugLoggingEnabled()) entitySafetyLogger.debug(message, *args)
}

private fun AttributeInstance.safeValue(entity: LivingEntity, attributeName: String, default: Double): Double = try {
    value
} catch (throwable: Throwable) {
    warnOnce(
        missingAttributeWarnings,
        "${entity.type.descriptionId}|$attributeName|value|${throwable.javaClass.name}",
        "Could not read value for attribute {} from entity type {}; using default {}",
        attributeName,
        entity.type.descriptionId,
        default,
        throwable
    )
    default
}

private fun Holder<Attribute>.safeAttributeName(): String = runCatching {
    registeredName
}.getOrElse {
    runCatching { "unregistered:${value().descriptionId}" }.getOrDefault("unregistered")
}

private fun warnOnce(keys: MutableSet<String>, key: String, message: String, vararg args: Any?) {
    if (GrimSaverConfig.warnLoggingEnabled() && keys.add(key)) entitySafetyLogger.warn(message, *args)
}
