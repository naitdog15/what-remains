# Dev Jars — what-remains

These jars are gitignored local dependencies. Copy them into `run/mods_raw/` before building
(`.\gradlew.bat stageRunMods` then publishes them into a local Maven repo for `fg.deobf`).
See `../../_tools/split-manifests/INSTANTIATION.md` §3 and `MIGRATION_MAP.md` section 4.

| Filename | Scope | Source location | sha256 |
|---|---|---|---|
| notminelegacy-1.0.0.jar | compile (mandatory sibling dep) | `../../_tools/built-jars/` (freshly built by the not-mine-legacy repo; `../../_tools/seed-jars/` is the bootstrap fallback) | `55b112e5f56132e82847bff0804365901d9dff10bc7c89e3688beb871426a6ea` |
| Jade-1.20.1-Forge-11.13.2.jar | compile (compileOnly, optional compat dep) | original workspace `modern_1.20.1/run/mods_raw/` (also mirrored in `run_cached/mods_raw`) | `31949971a7a8d5a5bc95ad9d400cbedfd2d65ffbfb3cea7c0b2fa3b47431a0a5` |
| curios-forge-5.14.1+1.20.1.jar | compile (compileOnly, optional compat dep) | original workspace `modern_1.20.1/run/mods_raw/` (also mirrored in `run_cached/mods_raw`) | `1e817919a35b37cf30524aaec73f0ca5130452f23f168f844854df282eb8e51f` |
| DungeonNowLoading-forge-1.20.1-2.2.jar | **runtime-only** (optional, dev-run only — NO `compileOnly` line) | original workspace `modern_1.20.1/run/mods_raw/` (also mirrored in `run_cached/mods_raw`) | `287b6ddbf4eceac67607114ee03b4bc08f7c355dfe7d1c2193f6ae884ec0c2f3` |

## Scope notes

**DungeonNowLoading is runtime-only and deliberately has no `compileOnly fg.deobf(...)` line in
`build.gradle`.** All Java-side access to DNL goes through reflection (`compat/dnl/`), so nothing in
`src/main/java` imports a DNL class and the module compiles with the jar absent. The coupling that
*does* need the exact jar is the pair of `@Pseudo` mixins in `whatremains.mixins.json`: they are
exact-ABI patches against **release 2.2**, which is why both `mods.toml`
(`versionRange="[2.2,2.3)"`) and `WhatRemainsMixinPlugin`'s independent early version gate refuse an
unreviewed DNL update rather than risk a required-mixin classload crash. "No compile import" does
NOT mean "runtime optional at any version" — stage this exact jar for any dev run that exercises the
Great Bottle o' Enchanting integration.

**Jade and Curios are `compileOnly`.** `compileOnly fg.deobf(files('run/mods_raw/<file>'))` resolves
the literal path, so both files must be present in `run/mods_raw/` or `compileJava` fails outright.
At runtime both are optional and gated (Jade via `compat/jade/`, only classloaded when Jade is
installed; Curios via a presence check in the corpse inventory restore path).

**notminelegacy is a mandatory sibling.** Its coordinate `local.runmods:notminelegacy:1.0.0` is
parsed from the FILENAME by `stageRunMods`, so the jar must stay named exactly
`notminelegacy-1.0.0.jar`. Renaming it breaks resolution with
`Error getting artifact: local.runmods:notminelegacy:1.0.0_mapped_official_1.20.1:null@pom`.

## Expected build warning

Gradle prints `Cannot deobfuscate dependency of type DefaultFileCollectionDependency` once per
`fg.deobf(files(...))` line. This warning is expected, was present in the monorepo too, and is not
an error.
