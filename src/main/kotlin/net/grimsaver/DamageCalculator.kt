package net.grimsaver

@Deprecated("Use DamagePredictor; retained as a compatibility facade for older GrimSaver code.")
object DamageCalculator {
    fun projectileDamage(projectile: ProjectileSnapshot, player: PlayerSnapshot): Double =
        DamagePredictor.projectile(projectile, player).damage

    fun meleeDamage(attacker: LivingSnapshot, player: PlayerSnapshot): Double =
        DamagePredictor.melee(attacker, player).damage

    fun fallDamage(player: PlayerSnapshot): Double = DamagePredictor.fall(player).damage
}
