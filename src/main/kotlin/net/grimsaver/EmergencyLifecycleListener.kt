package net.grimsaver

interface EmergencyLifecycleListener {
    fun onHomeCreated(homeName: String, threat: Threat) = Unit
    fun onTeleportExecuted(homeName: String) = Unit
    fun onCleanupStarted(homeName: String) = Unit
    fun onCleanupCompleted(homeName: String) = Unit
    fun onCleanupFailed(homeName: String, throwable: Throwable) = Unit
}
