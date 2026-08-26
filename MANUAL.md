# SkepiFB — Setup & Usage Manual

## 1. What SkepiFB Does

SkepiFB is a FastBuilder/bridging practice plugin for **Paper 1.21.8**.

Main features:

* FastBuilder arenas with multiple islands
* Timed building attempts
* Personal bests and leaderboards
* Coins and XP
* Levels and a level bossbar
* Replays
* Island cosmetics
* Block, tool, practice-block, animation, and firework shops
* Custom GUI menus
* Player stats/statboards
* Practice mode
* Optional LuckPerms rank colors/tags
* Optional Citizens replay NPCs

---

# 2. Requirements

### Required

* **Paper 1.21.8**
* **Java 21**
* **WorldEdit 7.2.16 or compatible**

WorldEdit is required for arena schematics to be pasted/restored.

### Optional

* **LuckPerms**

  * Used for rank/prefix colors and player tags.
* **Citizens**

  * Used for replay NPCs/animations.

The plugin can still run without the optional plugins.

---

# 3. Installation

Put the compiled `SkepiFB.jar` into:

```text
plugins/
```

Also install WorldEdit.

Start the server once.

SkepiFB creates its files inside:

```text
plugins/SkepiFB/
```

The important files are:

```text
config.yml
menu.yml
shop.yml
arena_settings.yml
arenas.yml
leaderboard.yml
stats.yml
scoreboard.yml
player-tags.yml
```

Replay data is also stored by player and arena.

After changing configuration, use:

```text
/fb reload
```

You normally do **not** need to restart the server.

---

# 4. Permissions

All `/fb` commands currently require:

```text
skepifb.admin
```

or the sender must be an operator.

Example with LuckPerms:

```text
/lp group admin permission set skepifb.admin true
```

This means normal players cannot use `/fb join` unless you give them the permission.

---

# 5. Basic Commands

## `/fb help`

Shows the command list.

```text
/fb help
```

The help text can be changed in `config.yml`.

---

## `/fb list`

Lists all created arenas.

```text
/fb list
```

It shows:

* Arena name
* Number of islands
* Layout
* Spacing
* Origin coordinates

Example:

```text
/fb list
```

---

## `/fb join <arena>`

Joins an arena.

```text
/fb join Normal
```

The player is given an available island and teleported there.

If no islands are free, the plugin reports that none are available.

---

## `/fb leave`

Leaves the current arena.

```text
/fb leave
```

The island is restored and becomes available again.

---

# 6. Creating an Arena

Before creating an arena, you need a WorldEdit schematic.

For example:

```text
bridge.schem
```

The plugin looks for schematics in the normal WorldEdit schematic folder.

Then use:

```text
/fb add <arena> <schematic> <islandCount> [spacing] [straight|diagonal]
```

### Example

```text
/fb add Normal bridge 8
```

Creates:

* Arena: `Normal`
* Schematic: `bridge`
* 8 islands
* 20 block spacing
* Straight layout

### Custom spacing

```text
/fb add Normal bridge 8 30
```

Uses 30 blocks between islands.

### Diagonal layout

```text
/fb add Normal bridge 8 30 diagonal
```

Currently does not work, it will in SkepiFB v1.1

### Straight layout

```text
/fb add Normal bridge 8 30 straight
```

---

# 7. Arena Locations

SkepiFB automatically chooses an arena origin.

New arenas are placed at separate X positions so they do not overlap.

The normal starting Y coordinate is:

```text
100
```

You normally don't need to manually edit `arenas.yml`.

---

# 8. Setting Spawn Facing

Stand where you want players to face and look in the desired direction.

Then:

```text
/fb setfacing Normal
```

The current yaw/pitch is saved.

Every island in that arena will use that facing direction when players spawn or respawn.

Example:

```text
/fb setfacing Normal
```

---

# 9. Removing an Arena

```text
/fb remove Normal
```

This removes the arena from `arenas.yml`.

**Important:** removing the arena configuration does not mean you should expect every pasted block in the world to be automatically deleted. Keep backups before making major changes.

---

# 10. Arena Settings

SkepiFB creates:

```text
arena_settings.yml
```

This controls settings specific to each arena.

Example:

```yaml
Normal:
  start: BLOCK
  finish: PLATE
```

## Start modes

### BLOCK

The attempt starts when the player begins placing blocks.

```yaml
start: BLOCK
```

### MOVE

The attempt starts when the player moves away from the starting area.

```yaml
start: MOVE
```

## Finish modes

### PLATE

The attempt finishes when the player reaches the finish pressure plate.

```yaml
finish: PLATE
```

### BED

The attempt finishes using the bed-based finish system.

```yaml
finish: BED
```

Example:

```yaml
Normal:
  start: BLOCK
  finish: PLATE

Hard:
  start: MOVE
  finish: PLATE
```

---

# 11. `config.yml`

This is the main configuration file.

It controls general gameplay, messages, hotbar, rewards, placeholders, replay controls, bossbars, and more.

---

## Block refill

```yaml
block-refill-threshold: 8
```

When a hotbar block item reaches this amount or lower, it is refilled to a full stack.

Example:

```yaml
block-refill-threshold: 4
```

The player gets a refill when they reach 4 or fewer blocks.

---

# 12. Default Arena

```yaml
default-arena: ""
```

If empty, the plugin uses the first available arena where appropriate.

You can specify one:

```yaml
default-arena: "Normal"
```

---

# 13. Finish Detection

```yaml
finish-detection:
  horizontal-shrink: 0.3
  jump-plates: 3.0
```

### `horizontal-shrink`

Controls how much of the pressure plate counts as the finish area.

```yaml
horizontal-shrink: 0.0
```

uses the full plate.

```yaml
horizontal-shrink: 0.3
```

makes the detection area smaller.

### `jump-plates`

Controls how high above the plate a player can be and still trigger it.

Example:

```yaml
jump-plates: 3.0
```

allows the player to trigger a plate several blocks above it.

---

# 14. Finish Title

```yaml
finish-title:
  title: "&bTime: &e%time%"
  subtitle: "&6%receivedcoins% Coins"
```

Example result:

```text
Time: 12.43
50 Coins
```

You can change the text and colors.

---

# 15. Actionbar

The actionbar shown during an attempt is configured here:

```yaml
actionbar:
  format: "&bCurrent Speed: &3%speed% m/s"
```

Example:

```yaml
actionbar:
  format: "&eTime: &f%timer% &7| Blocks: &f%blocks%"
```

---

# 16. Rewards

```yaml
finish-rewards:
  personal-best: 50
  normal-finish: 30
```

A new personal best gives 50 coins.

A normal finish gives 30 coins.

You can change them:

```yaml
finish-rewards:
  personal-best: 100
  normal-finish: 50
```

---

# 17. Finish Messages

There are two message groups:

```yaml
finish-messages:
  personal-best:
  normal-finish:
```

You can change each line.

Use:

```text
none
```

to hide a line.

Use:

```text
""
```

for a blank line.

---

# 18. Placeholders

These can be used in many messages.

| Placeholder                     | Meaning                        |
| ------------------------------- | ------------------------------ |
| `%timer%`                       | Current timer                  |
| `%time%`                        | Finish time                    |
| `%blocks%`                      | Blocks placed                  |
| `%coins%`                       | Total coins                    |
| `%receivedcoins%`               | Coins received from the finish |
| `%attempts%`                    | Attempts                       |
| `%pb%`                          | Personal best                  |
| `%pbdiff%`                      | Difference from PB             |
| `%speed%`                       | Current speed                  |
| `%averagespeed%`                | Average speed                  |
| `%xp%`                          | XP                             |
| `%level%`                       | Level                          |
| `%next_level_xp%`               | XP needed for next level       |
| `%bossbar_percent%`             | Level progress                 |
| `%player%`                      | Player name                    |
| `%mode%`                        | Current arena/mode             |
| `%top%`                         | Leaderboard percentile         |
| `%averagetime%`                 | Average completion time        |
| `%completions%`                 | Number of completions          |
| `%sessiontop1%`–`%sessiontop5%` | Session leaderboard entries    |

Example:

```yaml
format: "&b%player% &7| PB: &e%pb% &7| Attempts: &e%attempts%"
```

---

# 19. Chat Placeholders

You can make shortcuts players can type in chat.

Example:

```yaml
chat-placeholders:
  placeholders:
    ":)": "&a☺"
    "<3": "&c♥"
    "[pb]": "&bPB: &e%pb%"
    "[coins]": "&6Coins: &e%coins%"
```

A player typing:

```text
Good luck :)
```

can have the `:)` replaced by the configured symbol.

---

# 20. Practice Mode

```yaml
practice-mode:
  block-material: WHITE_TERRACOTTA
```

This controls the block given for practice mode.

Example:

```yaml
practice-mode:
  block-material: STONE
```

---

# 21. Hotbar

The hotbar is configured under:

```yaml
hotbar:
  items:
```

Each slot has:

```yaml
slot0:
  name: "&eBlock Item"
  material: SANDSTONE
  action: block
```

Important actions include:

```text
none
block
practice_block
tool
respawn
island_menu
replays_menu
settings_menu
leave
toggle_practice_mode
```

Example:

```yaml
slot8:
  name: "&cLeave Arena"
  material: BARRIER
  action: leave
```

The player can click that item to leave.

---

# 22. Replay Controls

Replay settings are under:

```yaml
replay:
```

Example:

```yaml
replay:
  restore-per-tick: 100
```

Replay hotbar items can also be customized:

```yaml
replay:
  hotbar:
    previous:
      material: STICK
      name: "&bPrevious Tick"

    toggle:
      running-material: RED_DYE
      running-name: "&cPause Replay"
      paused-material: LIME_DYE
      paused-name: "&aStart Replay"

    next:
      material: STICK
      name: "&bNext Tick"
```

---

# 23. Viewing Replays

To view another player's replays:

```text
/fb replays view <player>
```

Example:

```text
/fb replays view Steve
```

You must be inside an arena to browse replays.

---

# 24. Replay Debug Information

```text
/fb replayinfo
```

Shows replay information for yourself.

This is mainly useful for testing/debugging.

---

# 25. Level Bossbar

The level bossbar is configured under:

```yaml
bossbar:
```

Example:

```yaml
bossbar:
  enabled: true
  style: SOLID
```

You can disable it:

```yaml
bossbar:
  enabled: false
```

Available styles include:

```text
SOLID
SEGMENTED_6
SEGMENTED_10
SEGMENTED_12
SEGMENTED_20
```

The bossbar can also have different colors for different levels.

---

# 26. Statboard

The statboard appears above an island spawn.

Enable/disable it:

```yaml
statboard:
  enabled: true
```

Disable:

```yaml
statboard:
  enabled: false
```

Its position can be changed:

```yaml
offset:
  right: 2.0
  forward: 1.0
  up: 1.0
```

Its displayed lines are configurable:

```yaml
lines:
  - "%player%'s Statboard"
  - "&eBridging Statistics - %mode%"
  - "&bPersonal Best - &e%pb%"
  - "&bAverage Time - &e%averagetime%"
```

---

# 27. Statistic Reset

The statistic reset system is configured with:

```yaml
statistic-reset:
  cost: 75
```

So resetting costs 75 coins.

You can change the cost:

```yaml
statistic-reset:
  cost: 250
```

The confirmation/cancel buttons can also be changed.

---

# 28. `menu.yml`

`menu.yml` controls the normal GUI menus.

It is separate from `shop.yml`.

Important menu properties:

```yaml
title:
size:
items:
```

Example:

```yaml
my_menu:
  title: "&aMy Menu"
  size: 27
  items:
    13:
      material: EMERALD
      name: "&aClick Me"
      action: close_menu
```

`size` must be:

```text
9
18
27
36
45
54
```

---

# 29. Menu Item Actions

Common actions include:

```text
close_menu
previous_page
next_page
menu
mode_changer_menu
fastbuilder_settings_menu
toggle_practice_mode
cosmetic_none
island_1
island_2
...
mode
```

Example mode button:

```yaml
10:
  material: EMERALD
  name: "&aRanked"
  action: mode
  mode: "Ranked"
```

Clicking it switches the player to the `Ranked` arena/mode.

---

# 30. Creating Custom Menus

You can create a menu directly:

```text
/fb menu add mymenu
```

Then edit `menu.yml`.

Example:

```yaml
mymenu:
  title: "&bMy Menu"
  size: 27
  items:
    13:
      material: DIAMOND
      name: "&bDiamond"
      lore:
        - "&7A test button"
      action: close_menu
```

Reload:

```text
/fb reload
```

Open it:

```text
/fb menu open mymenu
```

---

# 31. Menu Commands

### Add

```text
/fb menu add <menuId>
```

### Remove

```text
/fb menu remove <menuId>
```

Built-in menus cannot be removed.

### List

```text
/fb menu list
```

### Open

```text
/fb menu open <menuId>
```

---

# 32. Built-in Menus

The plugin includes menus for:

* Islands
* Settings
* Mode switching
* FastBuilder settings
* Cosmetics
* Replays
* Shop categories

The bordered template menus have empty center slots so you can add your own buttons.

---

# 33. `shop.yml`

`shop.yml` controls the cosmetic/shop GUIs.

The available shop groups include:

```text
block_shop
tools_shop
reset_animation
firework_color
practice_shop
rankups
island_shop
tag_shop
```

Changes to `shop.yml` are loaded with:

```text
/fb reload
```

---

# 34. Shop Item Format

A normal shop item looks like:

```yaml
stone:
  material: STONE
  name: "&7Stone"
  price: 10
  slot: 10
  lore:
    - "&7A stone block"
```

### Important fields

`material`

The item/block shown.

`name`

The display name.

`price`

Coin cost.

`slot`

Inventory slot.

`lore`

Text shown when hovering.

`default`

Marks an item as the default item for that shop.

Example:

```yaml
default: true
```

---

# 35. Block Shop

The block shop contains categories such as:

```text
Sandstone
Colored Blocks
Wood
Stone
Variety
```

Example item:

```yaml
granite:
  material: GRANITE
  name: "&cGranite"
  price: 20
  slot: 10
```

Players buy blocks with coins and can equip owned blocks.

---

# 36. Tools Shop

The tools shop controls available tools.

Example:

```yaml
wooden_pickaxe:
  material: WOODEN_PICKAXE
  name: "&fWooden Pickaxe"
  price: 0
  default: true
```

---

# 37. Reset Animation Shop

Controls the animation used when an island is reset.

Example categories/items are configured the same way as other shops.

---

# 38. Firework Color Shop

Controls available firework colors.

Example:

```yaml
red_dye:
  name: "&cRed"
  price: 75
```

Players can purchase and equip colors.

---

# 39. Practice Block Shop

Controls blocks available in practice mode.

Example:

```yaml
white_terracotta:
  material: WHITE_TERRACOTTA
  name: "&fWhite Terracotta"
  price: 0
  default: true
```

---

# 40. Rankups

The `rankups` shop controls level/rank purchases.

Its items are configured just like the other shops.

---

# 41. Island Shop

Island cosmetics use **schematics**.

Example:

```yaml
classic:
  schematic: classic
  material: GRASS_BLOCK
  name: "&aClassic"
  price: 0
  slot: 10
```

When equipped, the corresponding schematic is pasted instead of the normal arena island schematic.

---

# 42. Adding an Island Cosmetic

The easiest way is:

```text
/fb island add <arena> <islandName> <schematic>
```

Example:

```text
/fb island add Normal castle castle
```

This adds:

* Cosmetic key: `castle`
* Schematic: `castle`
* Icon: Grass Block
* Default price: 2000 coins

The plugin also attempts to add the shop entry automatically.

---

# 43. Island Cosmetic Commands

### Add

```text
/fb island add Normal castle castle
```

### Remove

```text
/fb island remove Normal castle
```

### List

```text
/fb island list Normal
```

---

# 44. Coins

Administrators can modify online players' coins.

### Add

```text
/fb coins add <player> <amount>
```

Example:

```text
/fb coins add Steve 500
```

### Remove

```text
/fb coins remove Steve 100
```

### Set

```text
/fb coins set Steve 1000
```

---

# 45. XP

XP can also be changed manually.

### Add

```text
/fb xp add Steve 500
```

### Remove

```text
/fb xp remove Steve 100
```

### Set

```text
/fb xp set Steve 1000
```

The level UI updates when XP changes.

---

# 46. Leaderboards

### View a leaderboard

```text
/fb lb list Normal
```

### Add/update an entry

```text
/fb lb add Steve Normal 12.45
```

### Remove an entry

```text
/fb lb remove Steve Normal 12.45
```

The time must match the stored entry when removing it.

### Force a position

```text
/fb lb user Normal Steve 1 10.25
```

This manually sets a specific leaderboard position.

Positions must be valid leaderboard positions.

---

# 47. Testing Commands

These are mainly for server setup/testing.

### Set test spawn

```text
/fb test setspawn
```

### Start test

```text
/fb test start
```

### Exit test

```text
/fb test exit
```

---

# 48. Reloading

After changing configuration:

```text
/fb reload
```

This reloads:

* Main config
* Arena data
* Hotbar
* Scoreboard
* Shop configuration
* Menu configuration

If you edit YAML, make sure indentation is correct.

---

# 49. Typical Setup

A basic server setup would look like this:

### Step 1

Install:

```text
Paper 1.21.8
Java 21
WorldEdit
SkepiFB
```

### Step 2

Start the server.

### Step 3

Put your schematic into the WorldEdit schematic folder.

For example:

```text
bridge.schem
```

### Step 4

Create an arena:

```text
/fb add Normal bridge 8 20 straight
```

### Step 5

Set the spawn direction:

```text
/fb setfacing Normal
```

### Step 6

Give staff permission:

```text
skepifb.admin
```

### Step 7

Test it:

```text
/fb join Normal
```

### Step 8

Customize:

```text
plugins/SkepiFB/config.yml
plugins/SkepiFB/menu.yml
plugins/SkepiFB/shop.yml
plugins/SkepiFB/arena_settings.yml
```

### Step 9

Reload:

```text
/fb reload
```

---

# 50. Recommended File Guide

| File                 | Used for                                                     |
| -------------------- | ------------------------------------------------------------ |
| `config.yml`         | Main settings, messages, hotbar, rewards, bossbar, statboard |
| `menu.yml`           | Normal GUI menus                                             |
| `shop.yml`           | Shops and cosmetics                                          |
| `arena_settings.yml` | Start/finish settings and island cosmetics per arena         |
| `arenas.yml`         | Arena locations, islands, spacing, layout                    |
| `leaderboard.yml`    | Leaderboard data                                             |
| `stats.yml`          | Player stats, XP, coins, etc.                                |
| `scoreboard.yml`     | Scoreboard data/settings                                     |
| `player-tags.yml`    | Player tag data                                              |
| Replay folders       | Saved player replays                                         |

---

# 51. Color Codes

Most names/messages support Minecraft `&` color codes.

Examples:

```text
&a = green
&b = aqua
&c = red
&d = light purple
&e = yellow
&f = white
&7 = gray
&l = bold
```

Example:

```yaml
name: "&b&lFastBuilder"
```

creates a bold aqua name.

---

# 52. Important Notes

* Keep backups of `arenas.yml`, `stats.yml`, and `leaderboard.yml`.
* Make sure schematic names are correct.
* WorldEdit must be installed for schematic-based arenas.
* Use `/fb reload` after configuration changes.
* YAML indentation matters.
* `shop.yml` materials should be valid Minecraft materials.
* Block/practice shop blocks should be safe full blocks; avoid blocks with unwanted gravity, damage, or other effects.
* `skepifb.admin` gives access to the entire `/fb` command system.
* Removing an arena removes its stored arena configuration, so back up before making major changes.

---

# Quick Command Reference

```text
/fb help
/fb list
/fb add <arena> <schematic> <islands> [spacing] [straight|diagonal]
/fb remove <arena>
/fb setfacing <arena>
/fb join <arena>
/fb leave
/fb reload

/fb menu add <menuId>
/fb menu remove <menuId>
/fb menu list
/fb menu open <menuId>

/fb coins add <player> <amount>
/fb coins remove <player> <amount>
/fb coins set <player> <amount>

/fb xp add <player> <amount>
/fb xp remove <player> <amount>
/fb xp set <player> <amount>

/fb lb add <user> <mode> <time>
/fb lb remove <user> <mode> <time>
/fb lb list <mode>
/fb lb user <mode> <player> <position> <score>

/fb replays view <player>
/fb replayinfo

/fb island add <arena> <islandName> <schematic>
/fb island remove <arena> <cosmeticKey>
/fb island list <arena>

/fb test setspawn
/fb test start
/fb test exit
```

This is the basic reference you need to **install, run, configure, and manage SkepiFB without having to dig through the source code**.
