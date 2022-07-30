A forge mod for minecraft that lets you stick items to animals by ctrl-right-clicking them. Built jars are available here:

https://www.curseforge.com/minecraft/mc-mods/sticky-pets

A config file is generated at `<yourminecraftinstance>/config/stickypets-common.toml` when minecraft is run while this mod is installed. The default config file is as follows:

```toml
#Maximum number of items that can be stuck to pets. 0 => can't stick items to pets
#Range: > 0
max_stuck_items = 3
```

By default, all animal-type mobs can have items stuck to them. This can be configured via these entitytype tags:

* `stickypets:sticky` - Non-animal mobs in this tag will be made sticky.
* `stickypets:not_sticky` - Animal-type mobs in this tag will not be sticky.

Both tags are empty by default.
