# What Remains (`whatremains`)

Not Mine Legacy module: **a reimagined death-corpse system** for Minecraft 1.20.1 / Forge 47.4.10.
When a player dies their inventory is stored in a skeleton corpse entity that decays over time,
can be looted, and is buried by hitting it with a shovel.

Split out of the `temog-port` monorepo at `@1cedd534`. One repo = one write boundary: **only edit
files under this repo.** Sibling repos may be read, never written.

---

## Identity and clean-room discipline (READ FIRST)

This module is a **clean-room reimplementation of the CONCEPTS** of DrCyano's *Lootable Bodies*
(1.7.10). It is not a port of that mod's code, and no line of it descends from that code.

- The historical Lootable Bodies material lives **only** at
  `../../_reference/legacy/src/main/java/cyano/lootable/**`. That path is **read-only** and its
  contents must **never** be copied, pasted, adapted, or vendored into this repo — not into `src/`,
  not into `reference/`, not into a comment, not "just to compare".
- It is **decompiled, all-rights-reserved third-party source with no LICENSE or permission file
  anywhere in the tree** (`_reference/README.md`: "Lootable Bodies stays reference-only for
  `what-remains`"; `_reference/legacy/README.md` has the full licensing breakdown). Copying from it
  would be a licensing problem, not just a process one.
- `.gitignore` already excludes `reference/`, `references/`, `reference_package/`. Do not add an
  exception.
- Two Javadoc comments credit the concept lineage by name —
  `WhatRemains.java:22` and `CorpseEntity.java:80`. **Attribution text is correct and should stay.**
  Naming the inspiration is not a clean-room violation; importing its code is.
- Before any commit, the tracked file list must stay clean:
  ```bash
  git ls-files | grep -iE 'lootable|cyano|decompil'   # must print nothing
  ```

---

## Build and run

Java 17 toolchain, ForgeGradle **pinned to 6.0.54** (never a floating range), mappings
`official / 1.20.1`.

```powershell
.\gradlew.bat stageRunMods --offline      # ALWAYS first — see "Dependencies" below
.\gradlew.bat compileJava  --offline
.\gradlew.bat build        --offline      # -> build/libs/whatremains-1.0.0.jar
.\gradlew.bat runClient    --offline
.\gradlew.bat runServer    --offline
```

Drop `--offline` only when a task fails with a "not cached / offline mode" message.

**Gradle concurrency.** This workspace shares one Gradle cache with the sibling repos and runs with
the daemon disabled. Acquire a slot file (`../../_tools/.gradle-slot-A` or `-B`) before invoking
Gradle and delete it immediately after Gradle exits. A slot older than 15 minutes is stale and may
be replaced.

Expected, harmless build noise:
- `Cannot deobfuscate dependency of type DefaultFileCollectionDependency` — once per
  `fg.deobf(files(...))` line. Present in the monorepo too. Not an error.
- javac `Note: Some input files use or override a deprecated API` — the build suppresses
  `-Xlint:removal/-deprecation/-unchecked`; the mandatory summary Notes remain.

---

## Dependencies

Consumed as **artifacts**, never as source. Nothing here copies NML source.

| Jar | Scope | Notes |
|---|---|---|
| `notminelegacy-1.0.0.jar` | **mandatory** sibling, compile + runtime | `compileOnly fg.deobf('local.runmods:notminelegacy:1.0.0')` |
| `Jade-1.20.1-Forge-11.13.2.jar` | optional compat, `compileOnly` | must exist on disk or `compileJava` fails |
| `curios-forge-5.14.1+1.20.1.jar` | optional compat, `compileOnly` | must exist on disk or `compileJava` fails |
| `DungeonNowLoading-forge-1.20.1-2.2.jar` | optional, **runtime-only** | no `compileOnly` line — reflection-only at compile |

All four go in `run/mods_raw/` (gitignored). `stageRunMods` republishes them into a local Maven repo
under `local.runmods:<name>:<version>`, **parsing the coordinate from the FILENAME** — so
`notminelegacy-1.0.0.jar` must keep exactly that name or resolution fails with
`Error getting artifact: local.runmods:notminelegacy:1.0.0_mapped_official_1.20.1:null@pom`.

Sources and sha256 for every jar: **`DEV_JARS.md`**. Prefer `../../_tools/built-jars/` (freshly
built siblings); `../../_tools/seed-jars/` is the bootstrap fallback.

### DungeonNowLoading is exact-ABI pinned

DNL is optional and reflection-only in Java (`compat/dnl/`), so the module compiles and runs with it
absent. But the two `@Pseudo` mixins (`mixin/compat/dnl/`) are **exact-ABI patches against release
2.2**. Both `mods.toml` (`versionRange="[2.2,2.3)"`) and `WhatRemainsMixinPlugin`'s independent early
version gate deliberately refuse an unreviewed DNL update rather than risk a required-mixin
classload crash. **"No compile import" does not mean "runtime-optional at any version."** Bumping
that range means re-reviewing both mixins against the new DNL bytecode.

---

## Building Bridges depends on THIS repo — coordinate before changing the API

`building-bridges` declares a **hard, mandatory** `compileOnly fg.deobf('local.runmods:whatremains:1.0.0')`.
**18 BB files** consume this module's types. The load-bearing surface is exactly three types:

| Type | Used by BB for |
|---|---|
| `entity.CorpseEntity` | corpse targeting/goals; `dropVultureGore()` |
| `entity.DecayStage` | `FRESH`, `SKELETAL`, `COLLAPSE` gates on scavenger behaviour |
| `WhatRemainsConfig` | `debugFliesIgnoreDecayStage`, `debugVulturesIgnoreDecayStage`, `enableVultureCorpseFeeding`, `vultureFoodConsumeChance`, `flyDecayBonusPerFly` |

BB's consumers are its crow / fly / vulture scavenger bridges (`compat/alexsmobs/`,
`compat/naturalist/`, `events/WhatRemains*CorpseEvents.java`, `mixin/common/CorpseSmallEntityCollisionMixin`).

**Rule: any change to the shape of those three types — renaming or removing a `DecayStage`
constant, reordering the enum, renaming a `WhatRemainsConfig` static, or changing a `CorpseEntity`
signature BB calls — must be coordinated with the Building Bridges leader via the orchestrator
before it lands here.** Enum ordering is genuinely load-bearing: `ADVANCED_DECAY` was inserted
between `LATE_DECAY` and `SKELETAL` precisely so that existing render endpoints kept their
positions, and `DecayStage`'s own Javadoc points at
`buildingbridges.compat.alexsmobs.CorpseFlyAccessibility`. The coupling is documented in both
directions; keep it that way.

Never edit `repos/building-bridges` from this repo. Never `import` a `buildingbridges` class here —
the dependency arrow points **BB → whatremains** only.

---

## Layout

```
src/main/java/com/temog/notminelegacy/whatremains/
  WhatRemains.java  WhatRemainsConfig.java  GreatBottleConfig.java
  entity/       CorpseEntity, DecayStage, DeathCause, StuckProjectile
  client/       corpse render stack (flesh baker/mask/stitcher, layers, menu screen)
  environment/  data-driven corpse environments (data/whatremains/corpse_environment/*.json)
  events/       PlayerDeathHandler + XP-bottle events
  xp/ item/ loot/ menu/ network/ registry/
  compat/       dnl/ (reflection)  jade/  WRCuriosCompat
  mixin/        ItemBarMixin, ThrownExperienceBottleMixin, compat/dnl/*, WhatRemainsMixinPlugin
```

- One mixin config: `src/main/resources/whatremains.mixins.json`, wired **both** in `mods.toml`
  `[[mixins]]` and as `arg '-mixin.config=whatremains.mixins.json'` in `build.gradle`. A config
  registered in only one of the two silently never loads in dev — no error, no log line.
- `WhatRemainsMixinPlugin` gates the `compat.dnl.*` mixins on DNL presence **and** version.
- `META-INF/accesstransformer.cfg` is the **full monorepo AT** (72 lines), kept verbatim; it is both
  AT-wired in `build.gradle` and packaged into the jar. It widens some fields only sibling modules
  use — harmless (AT widening is additive) and deliberate for provenance.
- `run/` is gitignored, which is why `log4j2.xml` lives at tracked `config/log4j2.xml` and the run
  jvmArg points there.

---

## In-flight work (from `../../_context/memory/`)

Both systems are **compile-green but never playtested** — treat in-game behaviour as unverified.

- **Corpse decay / flesh system** — `project_corpse_decay_flesh_system.md`. Incident textures
  (char / frost / waterlogged, 36 PNGs) landed; **Java integration still pending**.
- **LB-03 XP-vessel** — `project_lb03_xp_bottle.md`. V2 orb-chunk rework (level-capacity 25/100,
  orb-ladder manifest, real-orb payout, top-right level badge). Compile green, in-game UNTESTED.

---

## Pointers

- Shared rules: `../../_context/rules/` (`forge-workspace.md`, `reference-first.md`, `workflow.md`).
  No rule in `_context/rules/path-scoped/` targets `whatremains`, so this repo carries no
  `.claude/rules/` — add one only if a genuinely whatremains-scoped rule appears.
- Project memory: `../../_context/memory/` — note `feedback_no_terminal_commits.md`
  (**the user commits via GitHub Desktop; do not run `git commit`/`git add` from the terminal** —
  the single migration commit in this repo's history was the sanctioned repo-bootstrap exception),
  `project_module_layout.md`, `feedback_reskin_needs_real_mechanic.md`.
- This repo has **no** `../../_reference/<repo>/` directory, deliberately: its historical reference
  is the legacy tree below, which is kept segregated. Do not create one.
- Legacy Lootable Bodies material: `../../_reference/legacy/src/main/java/cyano/lootable/**` —
  read-only, all-rights-reserved, **never copy in**. Read `../../_reference/legacy/README.md` first.
- Migration protocol: `../../_context/LEADER_PROTOCOL.md`; build recipe:
  `../../_tools/split-manifests/INSTANTIATION.md`.

**No publishing.** No remotes, no pushes, no public repos, no artifact uploads. Local development
only.
