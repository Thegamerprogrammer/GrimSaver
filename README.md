# GrimSaver / LastStand

Advanced client-side Fabric/Kotlin survival forecasting system that automatically saves emergency homes only when the player is predicted to be genuinely at risk of dying.

Unlike traditional damage-threshold systems, GrimSaver performs probabilistic survival simulations, escape-path analysis, combat forecasting, projectile trajectory prediction, environmental hazard analysis, and threat correlation before deciding whether a `/sethome` should occur.

---

# Features

✅ Fully Client-Side

✅ No GUI

✅ No HUD

✅ No Renderer

✅ No MCEF

✅ No Chromium

✅ No DJL

✅ No PyTorch

✅ No Server Plugins Required

✅ Multi-Threaded Threat Analysis

✅ False Positive Recovery System

✅ Survival Simulation Engine

✅ Escape Probability Modeling

✅ Projectile Trajectory Prediction

✅ Environmental Hazard Forecasting

---

# How It Works

GrimSaver continuously captures snapshots of the player's surroundings and evaluates:

* Nearby hostile mobs
* Enemy players
* Projectiles
* Environmental hazards
* Status effects
* Explosives
* Fall damage
* Escape routes
* Available defensive options

The mod then performs hundreds to thousands of simulation branches to estimate:

* Death Probability
* Survival Probability
* Escape Probability
* Expected Remaining Health
* Minimum Predicted Health
* Predicted Lethal Tick
* Confidence Level

If the probability of death exceeds configured thresholds, GrimSaver automatically executes:

```mcfunction
/sethome deathN
```

before the player dies.

---

# Survival Simulation Engine

Unlike traditional combat prediction systems that only compare:

```text
Incoming Damage > Current Health
```

GrimSaver simulates entire survival scenarios.

Each branch evaluates:

* Incoming damage timing
* Enemy aggression
* Projectile impacts
* Escape success chances
* Totem usage
* Regeneration
* Environmental hazards
* Follow-up attacks

The resulting forecast determines whether the player is genuinely likely to die.

---

# Escape Probability Analysis

A major source of false positives in older systems was:

```text
Player enters mob swarm
↓
Gets hit once
↓
System assumes death
↓
Triggers /sethome
```

The Escape Probability Engine evaluates whether survival is realistically possible.

Factors include:

## Terrain

* Open escape directions
* Cave systems
* Dead ends
* Corridors
* Doorways
* Water
* Hazard density

## Threat Pressure

* Nearby hostile mobs
* Pursuing entities
* Projectile saturation
* Explosive threats

## Defensive Options

* Placeable blocks
* Line-of-sight breaks
* Emergency wall placement
* Escape routes

A player in an open field surrounded by weak zombies may be considered survivable.

A player trapped in a cave while pursued by multiple threats may receive a much higher death probability.

---

# Threat Detection

## Players

The system evaluates:

* Weapon damage
* Attack cooldowns
* Sharpness
* Smite
* Bane of Arthropods
* Strength effects
* Weakness effects
* Sprint attacks
* Critical hits
* Mace attacks
* Custom NBT weapons

## Mobs

The system analyzes:

* AI state
* Targeting behavior
* Chase behavior
* Reach
* Attack intervals
* Special attacks

Supported hostile entities include:

* Zombies
* Husks
* Drowned
* Skeletons
* Strays
* Bogged
* Creepers
* Spiders
* Cave Spiders
* Endermen
* Piglins
* Piglin Brutes
* Hoglins
* Zoglins
* Blazes
* Ghasts
* Witches
* Ravagers
* Vindicators
* Evokers
* Pillagers
* Guardians
* Elder Guardians
* Wardens
* Withers
* Ender Dragons
* Breezes
* Phantoms
* Slimes
* Magma Cubes

and many more.

---

# Projectile Simulation

GrimSaver predicts impacts from:

* Arrows
* Tridents
* Fireballs
* Wind Charges
* Potions
* Fireworks
* Modded projectiles

Simulation accounts for:

* Gravity
* Drag
* Projectile velocity
* Hitbox expansion
* Impact timing
* Estimated damage
* Confidence scores

---

# Environmental Hazard Forecasting

## Status Effects

The system evaluates:

* Wither
* Poison
* Fire

while considering:

* Current health
* Regeneration
* Resistance effects
* Survival thresholds

## Fall Damage

Prediction includes:

* Fall distance
* Velocity
* Safe fall distance
* Feather Falling
* Protection enchantments
* Armor mitigation

## Additional Hazards

* Entity cramming
* Explosive pressure
* Environmental damage stacking

---

# Threat Correlation System

Instead of evaluating threats independently, GrimSaver builds a combat timeline.

Example:

```text
Arrow
+
Zombie Attack
+
Poison Tick
+
Fall Damage
```

can be correlated into a single lethal event.

Low-confidence threats are automatically rejected.

---

# Lightweight Threat Classification

A lightweight onboard classifier contributes threat severity estimates using:

* Health pressure
* Armor condition
* Enemy density
* Projectile density
* Explosive threats
* Escape probability

The classifier never directly triggers `/sethome`.

It only contributes confidence values used by the survival forecasting engine.

---

# Totem Awareness

GrimSaver detects:

* Offhand Totems
* Main-hand Totems
* Inventory Totems

Survival simulations account for:

* Totem activation
* Remaining health after activation
* Regeneration effects
* Follow-up damage
* Nearby hostile threats

This helps prevent unnecessary triggers when a player can realistically survive using a totem.

---

# False Positive Recovery System

After a home is created:

```mcfunction
/sethome deathN
```

the event enters a monitoring period.

If the player:

* Escapes
* Heals
* Stabilizes
* Survives the configured delay

the trigger may be classified as a false positive.

Unnecessary homes can then be removed automatically.

This significantly reduces clutter caused by temporary danger spikes.

---

# Storage

Homes:

```text
.minecraft/config/LastStand/DangerHomes/<sanitized-server>.txt
```

Logs:

```text
.minecraft/config/LastStand/logs.txt
```

Example:

```text
[2026-05-31 19:42:18]
death7
Reason: Survival forecast predicted 94.3% death probability
Source: Creeper + Arrow + Fall Damage
Confidence: 91%
Position: 124 63 -812
```

---

# Chat Commands

Commands are intercepted client-side and never sent to the server.

```text
.grimsaver
.gs
```

Displays recently saved homes for the current server.

Each entry is clickable and executes:

```mcfunction
/home deathN
```

---

# Configuration

Configuration file:

```text
.minecraft/config/LastStand/grimsaver.properties
```

Example:

```properties
enabled=true

riskEngineEnabled=true

projectileThreats=true
mobThreats=true
pvpThreats=true
fallThreats=true

survivalSimulationTicks=120
survivalSimulationBranches=512

survivalHealthThreshold=1.0

safetyMargin=2.0

scanEveryTicks=2

lethalConfidenceThreshold=0.75
```

---

# Architecture

Core systems:

```text
RiskEngine
SimulationEngine
EscapeProbabilityEngine
HealthForecastEngine
ThreatCorrelationEngine
UniversalThreatRegistry
ProjectileTrajectoryEngine
EntityCombatAnalyzer
MiniThreatClassifier
HealthVelocityTracker
BurstDetectionSystem
```

Threat analysis runs on a dedicated worker thread:

```text
GrimSaver-ThreatWorker
```

to minimize impact on Minecraft's client thread.

---

# Build

```bash
./gradlew build
```

Generated jars:

```text
build/libs/
```

---

# License

GPL-3.0 License

See the LICENSE file for details.
