# Installation

1. Back up your world and player data while Hytale/server is closed.
2. Choose the Hytale 0.7 file from [Disks releases](https://github.com/jkswoods/mega-bench-disks/releases) whose requirements include your exact game patch. Read its release notes for included features and known limits.
3. Install the required [Mega Bench](https://github.com/jkswoods/mega-bench/releases) companion API1 build specified by that Disks file. Mega Bench 0.2.1 and earlier cannot load this companion.
4. With the game/server stopped, place one compatible JAR for each mod in its `mods` directory. For a local game this is Hytale UserData's Mods directory; a dedicated server uses its own mods directory. Remove older copies of these two mods from global and world-specific locations. Leave unrelated mods alone.
5. Start the game/server and use a disposable world for the Alpha. The resource pack is included in the mod JAR.

## Playing on another machine

Download both compatible mod files on that machine. A fresh world starts fresh; transferring your workshop requires the **whole saved world and player data**, because disk contents belong to the world. Copying a cartridge item alone does not transfer its stored items. Close the game/server before copying a save and preserve its backup.

For a multiplayer test, install the pair on the host/server and follow the game's resource-pack prompts. Actual two-client compatibility is still being checked; do not use the Alpha for valuable shared storage yet.

## Updating and removing

Keep only one copy of each mod. Back up first and read the chosen file's migration notes. Empty stored items, eject every disk and recover your belongings before uninstalling. Keep the world backup with the compatible mod pair so a rollback has both code and contents.
