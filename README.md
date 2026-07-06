# World's End

A Fabric mod for Minecraft 26.2. When the Ender Dragon dies, a giant circle centered on world spawn (the entire world, 60 million blocks across) starts shrinking, deleting everything outside of it. Blocks near players break off and drift away into the sky while a boss bar counts down how far the edge is. With the default settings it takes a bit over 2 hours to reach spawn, and once it's done the overworld is just void.

Downloads are on [Modrinth](https://modrinth.com/mod/worlds-end) and CurseForge.

## ⚠️ Warning

This mod deletes terrain **permanently**, from the save file itself. There is no undo. Also important: the trigger is the dragon having been killed *at any point*, so installing this on a world where the dragon is already dead starts the destruction the moment the world loads. Removing the mod stops it from going further, but nothing comes back.

Keep backups. Really.

## Features

- Circular border that shrinks from the world edge (radius 30,000,000) to 0, following a configurable speed schedule (fast far away, slow near spawn).
- Blocks near players visually break off and float away, with breaking sounds. Both are budgeted per tick so it doesn't melt the server.
- Per-player boss bar showing the distance to the edge.
- Deletion is lazy: the radius is just a number until chunks are actually observed, so the huge starting radius costs nothing. No region file editing.
- Fluids stop cleanly at the edge, and chunks that load in beyond the border (including newly generated ones) get erased.
- Survives restarts, works in multiplayer (mod required on both server and client).

## Commands

Everything is under `/worldsend` and needs operator permission (level 2).

| Command | What it does |
|---|---|
| `/worldsend start` | Starts the end of the world without needing to kill the dragon. |
| `/worldsend pause` | Freezes the border where it is. Already-deleted terrain stays deleted. |
| `/worldsend resume` | Unfreezes it. |
| `/worldsend radius <blocks>` | Sets the radius directly (starts the end if it hasn't started). Shrink only — it can never grow, since what's outside is already gone. |

## Config

`config/worlds-end.json` is created on first launch:

- `speedMultiplier` — scales the whole thing. `2.0` = ~1 hour total, `0.5` = ~4 hours, etc.
- `speedSchedule` — the full speed table if you want finer control. Each entry is "above this radius, shrink this many blocks per tick".

Changes apply on world load, no need to restart the game.

## Building

You need JDK 25. Clone the repo and run:

```
./gradlew build
```

The mod jar ends up in `build/libs/` (the one without `-sources` in the name).

Requires Fabric Loader 0.19.3+ and Fabric API, on Minecraft 26.2.

## License

MIT. See [LICENSE](LICENSE).