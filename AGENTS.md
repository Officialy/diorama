# Diorama

## Tooling

modaccel (https://github.com/Noisemaker111/modding-accelerator, local clone `E:\w\scratch-modacc\modding-accelerator`) drives the running game, so agents can verify work and experiment in seconds. Building this tooling is part of the job.

When a modaccel change would make your task faster, or you catch yourself repeating a manual step in Minecraft, change modaccel first and use it in the task. Then open a PR upstream saying what it did in practice.

## Game tests

Game tests run on boat.dev, through the `boat-game-test` skill: anything that asks whether a feature works, what a screenshot shows, or whether a fix holds on another version. Dara uses this PC, and often plays on it. It's for what a boat VM can't do: fps and bake-speed measurements, GPU bake, pixel comparisons against an RTX image, modded installs, and anything driven on the desktop.

Don't queue a game test behind the Minecraft lock, and don't try the PC first because the lock looks free. `locked_run.py --shared` refuses to start without `--needs-pc '<why>'`.

If boat can't start a run, the skill's Limits section says what to do.

## Conventions

Xaero's Minimap and World Map are the standard Diorama follows, with JourneyMap as the second reference. Before deciding a behaviour, setting, default, name or file format, check what Xaero's does and match it. Depart from it where Diorama's 3D map calls for something else, and say why in the PR.

When a product call goes to Dara, lead with Xaero's precedent.

## Repository hygiene for the GTNH port

- Track source, pinned build inputs (including Gradle wrapper JARs), reusable architecture and test instructions.
- Keep machine paths/instance inventories, reference clones, screenshots, logs, generated audits and progress diaries in `/.local/`; existing local GTNH milestone notes are explicitly ignored. Do not copy credentials into these notes.
- Keep `.gitignore` current when adding local tools or generated output. Do not blanket-ignore documentation, JARs or lockfiles needed to reproduce a build.
- Before committing, inspect the staged file list and diff; keep reusable docs independent of ignored local evidence. Do not stage build caches, game directories or downloaded dependencies. A commit does not authorize a push or deployment.
