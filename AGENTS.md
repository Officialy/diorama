# Diorama

## Tooling

modaccel (https://github.com/Noisemaker111/modding-accelerator) drives the running game, so agents can verify work and experiment in seconds. Keep the local clone location in ignored machine-local notes rather than this file. Building this tooling is part of the job.

When a modaccel change would make your task faster, or you catch yourself repeating a manual step in Minecraft, change modaccel first and use it in the task. Then open a PR upstream saying what it did in practice.

## Game tests

Game tests run on boat.dev, through the `boat-game-test` skill: anything that asks whether a feature works, what a screenshot shows, or whether a fix holds on another version. Dara uses this PC, and often plays on it. It's for what a boat VM can't do: fps and bake-speed measurements, GPU bake, pixel comparisons against an RTX image, modded installs, and anything driven on the desktop.

Don't queue a game test behind the Minecraft lock, and don't try the PC first because the lock looks free. `locked_run.py --shared` refuses to start without `--needs-pc '<why>'`.

If boat can't start a run, the skill's Limits section says what to do.

## Conventions

Xaero's Minimap and World Map are the standard Diorama follows, with JourneyMap as the second reference. Before deciding a behaviour, setting, default, name or file format, check what Xaero's does and match it. Depart from it where Diorama's 3D map calls for something else, and say why in the PR.

When a product call goes to Dara, lead with Xaero's precedent.

## Repository hygiene for the GTNH port

- 1.7.10 development is currently local-only. Track source, tests, pinned build inputs (including wrappers), and only concise, low-churn documentation: major plans, settled decisions, structure and dependencies.
- Keep brief development memos, known-issue details, research, machine paths and run evidence in ignored `/.local/`; do not maintain milestone reports or duplicate code/test details in tracked docs. Never store credentials.
- Write handoff documents only when the user explicitly requests them, and only under `/.local/`. Keep routine replies brief; do not repeat unchanged issues with no identified functional impact, or weaken assertions to hide them.
- Keep ignore rules current without blanket-ignoring required build inputs. Review staged paths and diff before committing; no caches, game directories or downloaded dependencies. A local commit never authorizes push, release preparation, deployment or game launch.
