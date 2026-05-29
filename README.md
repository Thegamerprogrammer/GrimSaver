# GrimSaver / UniversalKeepInv

Silent client-side Fabric/Kotlin emergency death home saver based on the current LiquidBounce nextgen Minecraft/Kotlin/Fabric setup.

## What it does

GrimSaver (internally branded as LastStand in chat/files) runs without any GUI, HUD, renderer, MCEF, Chromium, DJL, PyTorch, or UI dependencies. It snapshots nearby projectiles/entities on the client thread, performs heavier threat calculations on a background `ExecutorService`, and sends:

```mcfunction
/sethome deathN
```

when predicted incoming damage can kill the player after the configured safety margin.

Records are stored per server in:

```text
.minecraft/config/LastStand/DangerHomes/<sanitized-server>.txt
```

Every trigger is also logged to:

```text
.minecraft/config/LastStand/logs.txt
```

Log format:

```text
[timestamp] death3 | Reason: Lethal Arrow from Power V bow | Damage: 18.4 | Pos: x y z
```

## Chat commands

Type either command in chat. The message is intercepted client-side and is not sent to the server:

```text
.grimsaver
.gs
```

The command prints the last 8-10 saved homes for the current server. Each `deathN` entry is clickable and runs `/home deathN`.

## Prediction scope

- Original projectile trajectory simulation inspired by LiquidBounce nextgen AutoDodge/trajectory ideas, with no rendering.
- Projectile, melee, fall and combined lethal prediction.
- Reads item components/NBT for projectile stacks, tipped arrows/potions, fireworks, and weapon/armor enchantments.
- Applies Minecraft-style armor/toughness and protection reductions with configurable sensitivity and cooldowns.

## Configuration

The first run creates:

```text
.minecraft/config/LastStand/grimsaver.properties
```

Important toggles:

- `projectileThreats`
- `meleeThreats`
- `fallThreats`
- `combineThreats`
- `safetyMargin`
- `scanEveryTicks`
- cooldown settings

## Build

```bash
./gradlew build
```

The mod jar is written to `build/libs/`.

## License
This project is licensed under the GPL-3.0 License - see the LICENSE file for details.
