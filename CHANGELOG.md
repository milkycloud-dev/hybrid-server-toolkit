# Changelog

## 1.0.8 (2026-10-01)

- `/deathbackup`: a nick resolves to snapshots even when typed in another case or missing from the profile cache. In offline mode the cache invents a UUID for any spelling, so it is used only when snapshots exist under it; otherwise the name stored inside the snapshots is matched. One unreadable file no longer stops the search.

## 1.0.7 (2026-10-01)

- Death snapshots. InventoryRollbackPlus only sees the Bukkit inventory, so Curios and Accessories slots (backpacks, rings) never reach its backups. At `LivingDeathEvent` the whole player NBT is saved to `notebuns/deaths/<uuid>/`, last 60 per player. It is taken at HIGHEST, before any mod moves items into drops, and written only if the death was not cancelled (Hardcore Revival cancels it for a knockout). Commands, op level 3: `/deathbackup list <nick>`, `/deathbackup view <nick> <n> [mods|inventory|ender] [page]`, which opens copies of the items in a chest.

## 1.0.6 (2026-10-01)

- Respawn position. Youer respawns the player NeoForge style: a new `ServerPlayer`, with `connection.player` switched only after `PlayerList.respawn` returns, so the position teleport inside it hit Paper's removed-player guard (`Attempt to teleport removed player X restricted`) and the client never got its position. The client then stood at its own spawn guess, and with a high `moved-too-quickly-multiplier` the server took that as a real move and counted the height difference as fall damage, sometimes in a loop. `connection.player` is now switched in `PlayerEvent.Clone`, before the teleport.
- Death rules per dimension. On Youer every dimension has its own game rules, and they had drifted. At every start `doImmediateRespawn` and `showDeathMessages` in each dimension are set to the overworld values.

## 1.0.5 (2026-09-28)

- A click on a slot past the Bukkit view of a modded menu (Aether accessories) counts as a click outside the window instead of throwing in every plugin that listens to `InventoryClickEvent`.

## 1.0.4 (2026-09-25)

First public release.

- Chunky pregeneration queue with `concurrent`, restart safety and pause handling.
- Disk check of region files before a world counts as done.
- Recipe book copy on respawn and recovery from completed advancements on login.
- Exit watchdog for shutdowns that leave the process alive.
- Aether dungeon blacklist filter uses the region-bound structure manager.
