# Minecraft Mods

Frankie's home for custom Minecraft (Java Edition) mods, built with [Fabric](https://fabricmc.net/).

| Mod | What it does | Status |
|---|---|---|
| [`drone`](mods/drone/) | A rideable quadcopter that flies like a real camera drone | Designing |

## Trying a mod

Each mod lives in `mods/<name>/` and is its own project. To launch a separate development copy of
Minecraft with that mod loaded (your real game and worlds are not touched):

```bash
cd mods/drone && ./gradlew runClient
```

Designs and plans for every mod are in [`docs/superpowers/`](docs/superpowers/).
