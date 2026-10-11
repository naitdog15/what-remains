# Not Mine Legacy: What Remains

What Remains is a reimagined death system. When you die, your inventory is not dropped on the ground in the usual way. Instead it is stored in a skeleton corpse entity at the place you died. The corpse holds your items until you come back for them, and it can be looted by you or by anyone else who reaches it first.

The corpse is configurable: you can change how much it can hold, how tough it is, and what can hurt it. The aim is to give a death a place in the world, rather than a single moment where everything disappears.

## Features

- **Corpse on death.** Your inventory goes into a skeleton corpse entity at the point of death. Its health and inventory size (default 54 slots) are configurable.
- **Burial.** The corpse can be buried with a shovel. The number of hits needed is configurable.
- **Extra loot on the corpse.** Bones and rotten flesh can be added to the corpse, which can be turned on or off.
- **Soulbound enchantment.** Items with Soulbound have a per-level chance to be kept on death. The enchantment also handles the Curse of Binding and the Curse of Vanishing.
- **Vulnerability.** You choose which damage sources can damage the corpse.
- **XP bottle.** A bottle item that stores experience points, using the shared XP maths from Not Mine Legacy.

## Dependencies

What Remains requires Not Mine Legacy. The corpse environment sampling, the network channel and the XP helpers all come from that library, so it must be installed alongside this mod.

Optional integrations:

- Curios: optional. Detected at runtime when installed.
- Jade (version 11 or newer): adds tooltips on the client. This is client-side only.

## Installation

Put the What Remains jar and the Not Mine Legacy jar in your `mods` folder. Both are needed for the mod to load.

## Configuration

Settings are in `config/whatremains.toml`. They cover:

- corpse health and inventory size
- how many burial hits are needed
- whether bones and rotten flesh are added
- item damage on death
- Soulbound keep chances
- which damage types can affect the corpse

## Licence

What Remains is released under the MIT licence. See the `LICENSE` file in this repository for the full text.

## Third-party software

The What Remains jar does not include Not Mine Legacy, Curios, Jade, Minecraft, or Forge. Each of those is a separate download and is licensed by its own authors. Please check the licence of each one you install alongside this mod.

## Credits

Temog.
