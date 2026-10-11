---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
<!-- refreshed: 2026-10-11 -->

# Architecture

**Analysis Date:** 2026-10-11

## System Overview

The single most important fact: **one APK, two runtime planes, separated by a process boundary.**

The **UI side** runs in the module's own process (`top.honorhook`). It never touches host memory. It only
writes booleans into LSPosed's *remote preferences* store. The **hook side** runs inside six foreign processes
and only mutates memory. The two planes share nothing but a keyspace of string literals declared once in
`app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt`.

```
                    ┌───────────────────────────────────────────────────────────────┐
                    │  UI SIDE  — process: top.honorhook (the module APK)           │
                    │                                                               │
  Launcher icon ───▶│  HonorHookApp (Application)  ── app/app/src/main/kotlin/      │
                    │    │   top/honorhook/ui/HonorHookApp.kt:11                    │
                    │    ├─ AppSettings.init / FeatureStore.init                    │
                    │    └─ XposedServiceHelper.registerListener  :17               │
                    │             │                                                │
                    │             ▼ Binder connect                                 │
                    │        FrameworkState (ui/FrameworkState.kt) — .active/.name    │
                    │             │                                                │
                    │             ▼ current()                                      │
  user taps switch ▶│        FeatureStore.setEnabled(key, enabled)                  │
                    │          ├─ mirror: mutableStateMapOf  (Compose observable)    │
                    │          ├─ local SP  "honorhook_features"                    │
                    │          └─ XposedService.getRemotePreferences("honorhook_config")
                    │                        .edit().putBoolean(key, enabled).apply() │
                    │             (returns Boolean = downstream success)            │
                    └──────────────────────────┬────────────────────────────────────┘
                                               │  libxposed remote prefs  (group
                                               │  "honorhook_config"  — FeatureCatalog.kt:12)
                                               ▼
                        ╔══════════════════════════════════════════════════╗
                        ║   LSPosed framework  (Zygote, /data/adb/lspd)    ║
                        ╚══════════════════════════════════════════════════╝
                                               │  read once at package load
                                               ▼
                    ┌───────────────────────────────────────────────────────────────┐
                    │  HOOK SIDE — injected into 6 host processes (memory only)     │
                    │                                                                       │
  java_init.list ──▶│  HookEntry : XposedModule  (xposed/HookEntry.kt:17)              │
  HookEntry.kt:19  │    onPackageLoaded(param)                                            │
                    │      :21  RemoteConfig.attach(getRemotePreferences(REMOTE_GROUP))   │
                    │      :26  TargetRegistry.find(param.packageName)                   │
                    │      :27  TargetRegistry.isInstalled(target)  (object-level dedup) │
                    │      :28  target.install(this, param)                              │
                    │      :29  TargetRegistry.markInstalled(target)                     │
                    │            │                                                      │
                    │            ▼ each TargetHook gates on RemoteConfig.isEnabled(key)  │
                    │            │   (FeatureCatalog key literals — the shared keyspace)  │
                    │            ▼                                                      │
                    │      MountEngine.attemptMount(pending, module, loader)              │
                    │         └─ MountScheduler (1 HandlerThread "HonorHook-mount")       │
                    │         └─ 2 lazy retry rounds @ 500ms / 2000ms                   │
                    │         └─ MethodResolver.resolveMethod  (2-track)                 │
                    │              track 1: direct reflection (no cache)                  │
                    │              track 2: DexKit feature fallback (content anchors)    │
                    │         └─ module.hook(m).setExceptionMode(PROTECTIVE)             │
                    │         └─ log "$hookName: installed N/M"  → LSPosed module log    │
                    └───────────────────────────────────────────────────────────────┘
                                               │
              ┌──────────────┬─────────────────┼─────────────┬──────────────┬───────────────┐
              ▼              ▼                 ▼             ▼              ▼               ▼
      thememanager   packageinstaller       settings      exam8          launcher       systemui
      ThemeStoreHook   InstallerHook       SettingsHook   Exam8Hook  LauncherCardTitleHook SystemUiHook
        (43 pts)         (11 pts)           (1 pt)       (10 pts)        (1 pt)          (1 pt)
```

Note the **write-false-to-disable / effective-next-injection** asymmetry: the UI writes `false` only when
the user turns a feature off; everything else (`missing`, `exception`, `never written`) reads as `true`
(`app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt:31-32`). The hook side snapshots the
prefs **once** at `onPackageLoaded` (`HookEntry.kt:21`) — so a toggle has no effect until the target
process is killed and relaunched.

## Component Responsibilities

| Component | Responsibility | File |
|---|---|---|
| `HookEntry` | libxposed entry point; injects remote prefs, dispatches by package, swallows all install failures | `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt:17` |
| `TargetHook` | Adapter interface every host target implements (`packageName`, `install`) | `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetHook.kt:25` |
| `logDisabled` | Emits an INFO trace when a whole target is skipped because its remote switch is off (so "switched off" is distinguishable from "never injected") | `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetHook.kt:21` |
| `TargetRegistry` | Static package→target map + object-level installed set (alias packages sharing a process must not double-mount) | `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt:11` |
| `MountEngine` | Shared mount machinery: lazy retry, failure grading, `PROTECTIVE` hooking, host-version self-check, `installed N/M` summary, judge log throttling | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt:9` |
| `MountEngine.Task` / `DexHint` / `Result` | Minimal protocol each target's mount table implements; `Result = OK / FAILED / NOT_RESOLVED` | `MountEngine.kt:12-36` |
| `MethodResolver` | Two-track target resolution: direct reflection first (uncached, bets on late class load), DexKit feature fallback second (cached per `ClassLoader` in `WeakHashMap` buckets) | `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt:8` |
| `DexKitHelper` | `libdexkit.so` lazy load, process-level `DexKitBridge` cache, 4-dimension feature matching (`usingStrings` / `paramTypes` / `declaredClassStrings` / `declaredClassFieldTypes`), synthetic/bridge/static filtering | `app/app/src/main/kotlin/top/honorhook/xposed/core/DexKitHelper.kt:12` |
| `MountScheduler` | Single shared `HandlerThread` named `HonorHook-mount`; all mounts and retries post here | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountScheduler.kt:7` |
| `RemoteConfig` | Hook-side switch reader; `attach(prefs)` injection point, `isEnabled(key)` default-true hot path (local map read, no Binder) | `app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt:16` |
| `CloudSwitchTable` | Theme-store cloud-switch forced-value table (16 entries) — the single place key→value lives | `app/app/src/main/kotlin/top/honorhook/xposed/data/CloudSwitchTable.kt:4` |
| `AdSlotFilter` | Theme-store server slot-type / targetId prune table (5 types + 2 ids) | `app/app/src/main/kotlin/top/honorhook/xposed/data/AdSlotFilter.kt:4` |
| `BottomTabFilter` | Bottom-tab prune table (VIP=3, WELFARE=12) + `MIN_LIST_SIZE` floor that prevents the host's hardcoded fallback branch | `app/app/src/main/kotlin/top/honorhook/xposed/data/BottomTabFilter.kt:4` |
| `InstallerConfigTable` | Package-installer gate constants (`SAFE_MODE_ALL_OFF_JSON`, `AD_REQUEST_NOT_SUPPORTED`, …) | `app/app/src/main/kotlin/top/honorhook/xposed/data/InstallerConfigTable.kt:4` |
| `FeatureCatalog` | **Single source of truth** for the remote-prefs group name and all 13 feature keys (title + description), shared verbatim by both planes | `app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt:9` |
| `ThemeStoreHook` | Theme-store target: 4 feature gates → 43 mount tasks, 9 `MountKind` branches | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/ThemeStoreHook.kt:14` |
| `ThemeStoreParts` | Theme-store shared parts: class-name constants, `MountTask` / `MountKind` / `UriOwner`, `addConst` / `addForceArg` builders, route predicates, throttled judge loggers | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/ThemeStoreParts.kt:15` |
| `CollectorsRes` | Theme-store mount-table builders: F1 VIP + retention dialog, F2 free/price/bean, F3/F4 trial + apply state | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/CollectorsRes.kt:6` |
| `CollectorsSource` | Theme-store mount-table builders + runtime: download chain, cloud switches, slot prune, bottom-tab prune, wallpaper path fix, `fixVipState` | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/CollectorsSource.kt:14` |
| `InstallerHook` | Package-installer target: 4 feature gates → 11 tasks; 4 `MountKind`s incl. `CHECK_BYPASS` and `FINGERPRINT_BYPASS` | `app/app/src/main/kotlin/top/honorhook/xposed/target/InstallerHook.kt:13` |
| `Exam8Hook` | exam8 target: 2 feature gates → 10 tasks; `CONST` + `CONDITIONAL` (privilege-bit-scoped short-circuit) | `app/app/src/main/kotlin/top/honorhook/xposed/target/exam8/Exam8Hook.kt:32` |
| `SettingsHook` | Settings target: single task, after-semantics `Q3` decision point + runtime `getIdentifier` view lookup | `app/app/src/main/kotlin/top/honorhook/xposed/target/SettingsHook.kt:13` |
| `LauncherCardTitleHook` | Launcher target: single task, `ThemeUtilImpl.isHiddenTitle()` → true (host's own empty-state branch) | `app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherCardTitleHook.kt:31` |
| `SystemUiHook` | SystemUI target: single task, pre-process arg rewrite on `updateSubs(List)` (overload disambiguated by `paramTypes`) | `app/app/src/main/kotlin/top/honorhook/xposed/target/SystemUiHook.kt:31` |
| `HonorHookApp` | Module-side Application; initializes settings + feature store, registers the libxposed service listener | `app/app/src/main/kotlin/top/honorhook/ui/HonorHookApp.kt:11` |
| `FrameworkState` | Holds the `XposedService` binder; publishes `active` / `frameworkName` / `frameworkVersion` / `apiVersion` as Compose state. **Only a bound binder counts as "activated"** | `app/app/src/main/kotlin/top/honorhook/ui/FrameworkState.kt:14` |
| `FeatureStore` | UI-side switch store: local SP mirror + Compose-observable map + remote write; returns whether the remote write succeeded | `app/app/src/main/kotlin/top/honorhook/ui/FeatureStore.kt:16` |
| `AppSettings` | Appearance settings (color mode / Monet / key color / glass / floating bar) in SP `honorhook_settings` + `mutableStateOf` | `app/app/src/main/kotlin/top/honorhook/ui/AppSettings.kt:51` |
| `MainActivity` + `AppRoot` + `HonorHookBottomBar` | Single Activity, 3 tabs (首页 / 功能 / 设置), glass floating bottom bar, backdrop layering | `app/app/src/main/kotlin/top/honorhook/ui/MainActivity.kt:61` |
| `HomeScreen` | Tab 1 — framework status card + device info | `app/app/src/main/kotlin/top/honorhook/ui/screen/HomeScreen.kt:78` |
| `FeaturesScreen` | Tab 2 — target list → per-feature switch list; renders both the "LSPosed 未激活" and "改动何时生效" notice cards | `app/app/src/main/kotlin/top/honorhook/ui/screen/FeaturesScreen.kt:86` |
| `SettingsScreen` / `ThemeSettingsScreen` / `MiuixBackButton` | Tab 3 — appearance + theme pages | `app/app/src/main/kotlin/top/honorhook/ui/screen/SettingsScreen.kt:59`, `.../ThemeSettingsScreen.kt`, `.../MiuixBackButton.kt` |
| `component/*` + `component/liquid/*` | Vendored UI parts (FloatingBottomBar, InteractiveHighlight, DampedDragAnimation, DragGestureInspector) + Miuix/glass theme bridge | `app/app/src/main/kotlin/top/honorhook/ui/component/…` |
| `DecisionTablesTest` | JVM regression pinning the 4 decision tables' contracts (table entries match, out-of-table input falls back safe) | `app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt:17` |

## Pattern Overview

| Pattern | Where | Evidence |
|---|---|---|
| Registry / adapter dispatch | Hook entry resolves a `TargetHook` by package; adding a target never touches `HookEntry` | `TargetRegistry.kt:16-25`, `HookEntry.kt:26-28` |
| Data table as single source of truth | Cloud switches / ad slots / bottom tabs / installer gates are pure data objects, no hook logic | `xposed/data/CloudSwitchTable.kt`, `AdSlotFilter.kt`, `BottomTabFilter.kt`, `InstallerConfigTable.kt` |
| Declarative mount table | A target builds a `List<MountTask>` (plain data), engine owns resolution/retry/grading | `ThemeStoreHook.kt:29-51`, `InstallerHook.kt:48-65` |
| Kind-dispatched interceptor | One `when (task.kind)` builds the interceptor; per-target kinds are private enums | `ThemeStoreParts.kt:75`, `ThemeStoreHook.kt:66-148`, `InstallerHook.kt:277` |
| Two-track resolution with content anchor | Direct reflection (uncached) → DexKit features (cached); no anchor ⇒ honest skip | `MethodResolver.kt:54-127`, `MethodResolver.kt:89-99` |
| Default-on gating | Absent / unreadable / unwritten ⇒ `true`; disable requires an explicit `false` | `RemoteConfig.kt:31-32`, `FeatureStore.kt:38` |
| Write-local + write-remote, report the remote result | UI never claims success it can't deliver | `FeatureStore.kt:52-67` |
| Compose-observable mirror | `mutableStateMapOf` as the single read path (SP is not snapshot state) | `FeatureStore.kt:32`, `FeaturesScreen.kt:495` |
| Graceful degradation everywhere | `runCatching` at every boundary; failure ⇒ proceed with original behaviour | `InstallerHook.kt:368`, `CollectorsSource.kt:148-150`, `SystemUiHook.kt:89-90` |
| Vendored upstream component | Ported verbatim, marked `vendored`, not refactored for line count | `ui/component/FloatingBottomBar.kt`, `ui/component/liquid/*` (per `docs/ui-miuix-overview.md:32`) |
| Anti-corruption between two planes | A string keyspace (`FeatureCatalog`) is the only coupling; each side owns its own copy of the runtime | `FeatureCatalog.kt:12` |

## Layers

```
┌──────────────────────────────────────────────────────────────────┐
│ L4  Module metadata      META-INF/xposed/{java_init.list,         │
│                          module.prop, scope.list}                  │
├──────────────────────────────────────────────────────────────────┤
│ L3  Target adapters      target/*Hook.kt, target/themestore/*     │
│                          (per-package mount tables + interceptors)  │
├──────────────────────────────────────────────────────────────────┤
│ L2  Mount infrastructure  xposed/core/{MountEngine, MethodResolver,│
│                          DexKitHelper, MountScheduler}            │
├──────────────────────────────────────────────────────────────────┤
│ L2  Decision tables       xposed/data/{CloudSwitchTable,           │
│                          AdSlotFilter, BottomTabFilter,           │
│                          InstallerConfigTable, RemoteConfig}       │
├──────────────────────────────────────────────────────────────────┤
│ L1  Entry + registry      xposed/HookEntry.kt, target/             │
│                          {TargetHook, TargetRegistry}              │
├══════════════════════════ PROCESS BOUNDARY ═══════════════════════┤
│ L1  Shared contract       top/honorhook/FeatureCatalog.kt          │
├──────────────────────────────────────────────────────────────────┤
│ L2  UI state              ui/{FrameworkState, FeatureStore,        │
│                          AppSettings}                              │
├──────────────────────────────────────────────────────────────────┤
│ L3  UI screens            ui/screen/{Home,Features,Settings,       │
│                          ThemeSettings}Screen.kt                   │
├──────────────────────────────────────────────────────────────────┤
│ L4  UI components         ui/component/**                          │
└──────────────────────────────────────────────────────────────────┘
```

Layering rules enforced by the code: `L3` never constructs a resolver or a thread — it produces data and
delegates (`InstallerHook.kt:324-393` calls `engine.mount`; `ThemeStoreHook.kt:51` calls `engine.attemptMount`).
`L4` never talks to `LSPosed` directly. The only cross-boundary symbols are `FeatureCatalog`'s
`REMOTE_GROUP` and the 13 `KEY_*` literals.

## Data Flow

### Hook registration (startup, every host process)

1. LSPosed reads `app/app/src/main/resources/META-INF/xposed/module.prop:1-4` → `minApiVersion=102`,
   `targetApiVersion=102`, `staticScope=true`, `exceptionMode=protective`; scope from
   `app/app/src/main/resources/META-INF/xposed/scope.list:1-6` (the 6 package names).
2. LSPosed instantiates the class named in
   `app/app/src/main/resources/META-INF/xposed/java_init.list:1` = `top.honorhook.xposed.HookEntry`
   (kept by name: `app/app/proguard-rules.pro:17`).
3. Per package: `HookEntry.onPackageLoaded` at `xposed/HookEntry.kt:19`.
4. Remote prefs snapshot: `HookEntry.kt:21` →
   `RemoteConfig.attach(getRemotePreferences(FeatureCatalog.REMOTE_GROUP))`
   (`FeatureCatalog.kt:12` = `"honorhook_config"`); read failure ⇒ `null` ⇒ everything default-on.
5. Dispatch: `HookEntry.kt:26` `TargetRegistry.find(param.packageName)`; dedup by **object identity** at
   `HookEntry.kt:27` (`isInstalled`, `TargetRegistry.kt:38`).
6. `target.install(this, param)` at `HookEntry.kt:28`; e.g. `ThemeStoreHook.install` at
   `xposed/target/themestore/ThemeStoreHook.kt:21` calls `engine.begin(sourceDir, HOST_VERSION_BASELINE)`
   (`:24`) then feature-gates its `collect*` calls (`:30-49`) against `RemoteConfig.isEnabled`.
7. `engine.attemptMount(pending, module, loader)` at `ThemeStoreHook.kt:51` →
   `MountEngine.attemptMount` at `core/MountEngine.kt:64`; host-version self-check posted at
   `MountEngine.kt:72` (1000 ms, because `Application` doesn't exist yet at `PackageLoadedParam` time).
8. Per task: `MountEngine.resolveTask` (`MountEngine.kt:113`) → `MethodResolver.resolveMethod`
   (`MethodResolver.kt:41`) → track 1 direct reflection (`MethodResolver.kt:54-79`, uncached) →
   track 2 DexKit (`MethodResolver.kt:112-126`, `DexKitHelper.findMethodByFeatures` at
   `DexKitHelper.kt:89`). Null ⇒ `NOT_RESOLVED` (`MountEngine.kt:83`) ⇒ retry at 500 ms then 2000 ms
   (`MountEngine.kt:206-208`, max 2 rounds at `MountEngine.kt:206`).
9. Mount: `MountEngine.mount` at `MountEngine.kt:145` →
   `module.hook(method).setExceptionMode(PROTECTIVE).setId(task.id).intercept(...)` at
   `MountEngine.kt:152-157`. Registration-time throw ⇒ `FAILED`, logged WARN, siblings unaffected
   (`MountEngine.kt:160-162`).
10. Judge log: `ThemeStoreHook.kt:30` `target installed: <pkg>`; summary line
    `"$hookName: installed $okCount/$totalCount"` at `MountEngine.kt:135`.

### Switch-toggle flow (UI side → next injection)

1. User flips `SwitchPreference` → `FeatureStore.setEnabled(feature.key, it)`
   at `ui/screen/FeaturesScreen.kt:527`.
2. Mirror update (Compose observable) `FeatureStore.kt:53`; local SP write `honorhook_features`
   `FeatureStore.kt:54`.
3. Binder gate: `FrameworkState.current()` at `FeatureStore.kt:55`; null ⇒ log WARN and **return false**
   (`FeatureStore.kt:56-58`) — no remote write, UI shows the "LSPosed 未激活" card
   (`FeaturesScreen.kt:559-562`).
4. Remote write: `service.getRemotePreferences("honorhook_config").edit().putBoolean(key, enabled).apply()`
   at `FeatureStore.kt:60-62`; exception ⇒ log WARN and **return false** (`FeatureStore.kt:63-66`).
5. Effect is deferred: the next target process launch re-reads the same key at
   `HookEntry.kt:21` → `RemoteConfig.isEnabled` (`RemoteConfig.kt:31`) gates `collect*` calls. The UI states
   this in a notice card (`FeaturesScreen.kt:563`: "开关在目标进程下次启动时读取").

### Runtime intercept (inside a hooked process)

Example — theme-store cloud switch:
`MountKind.KEY_OVERRIDE` branch at `ThemeStoreHook.kt:82-91` reads arg 0, looks it up in
`CloudSwitchTable.forced(key)` (`CloudSwitchTable.kt:47`, O(1) hash), short-circuits on hit, otherwise
`chain.proceed()`. Hit is logged, throttled to 2 per key, at `ThemeStoreParts.kt:220-225`.

Example — bottom-tab prune with a safety floor:
`MountKind.BOTTOM_TAB_PRUNE` at `ThemeStoreHook.kt:102-109` calls
`pruneBottomTabPayload` (`CollectorsSource.kt:211`), which drops ids 3/12 per `BottomTabFilter`
(`BottomTabFilter.kt:15-27`) but abandons pruning entirely if the result would fall below
`BottomTabFilter.MIN_LIST_SIZE = 3` (`BottomTabFilter.kt:39`) — because the host falls back to a
hardcoded tab list below that size, which would resurrect the VIP tab.

Example — installer security-check bypass with "fail open":
`MountKind.CHECK_BYPASS` at `InstallerHook.kt:344-370` builds a pass result from the host's own static
factory, delivers it on the main looper to mimic async semantics, and returns `null`; if **any** step
fails it calls `chain.proceed()` (`InstallerHook.kt:368`) so the install flow never deadlocks.

## Key Abstractions

1. **`TargetHook`** (`xposed/target/TargetHook.kt:25`) — the whole hook side is "one interface + one map".
   `packageName` is the lookup key; `install` is the entire contract. No base class, no hooks into the
   framework beyond libxposed.
2. **`MountEngine.Task`** (`core/MountEngine.kt:23-36`) — the mount-table row: stable `id`, class/method
   signature, human-readable `description`, optional `dex` hint, optional `resolveValue` for
   load-dependent values. The engine handles everything else.
3. **`MountEngine.Result`** (`MountEngine.kt:12`) — the failure-grading vocabulary.
4. **`MethodResolver.resolveMethod`** (`core/MethodResolver.kt:41`) — hides "how do I actually get a
   `Method` for a possibly-obfuscated class" behind one call; caching is per-`ClassLoader` and weak-keyed
   (`MethodResolver.kt:18,21`) to avoid cross-loader cross-contamination and loader leaks.
5. **`DexHint`** (`MountEngine.kt:15-20`) — the 4-dimension content anchor (`strings`, `paramTypes`,
   `classStrings`, `classFieldTypes`). Anchor-less obfuscated targets are *deliberately* unresolvable.
6. **`FeatureCatalog`** (`FeatureCatalog.kt:9`) — the cross-process contract. Pure Kotlin, zero Compose /
   miuix / Android deps so both planes can depend on it.
7. **`RemoteConfig` / `FeatureStore`** (`xposed/data/RemoteConfig.kt:16`, `ui/FeatureStore.kt:16`) —
   symmetric readers/writers of the same remote keyspace.

## Entry Points

| Entry | Kind | Path |
|---|---|---|
| LSPosed module entry | `XposedModule.onPackageLoaded` override | `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt:19` |
| Module class declaration (for the framework) | `java_init.list` | `app/app/src/main/resources/META-INF/xposed/java_init.list:1` |
| Module descriptor | `minApiVersion` / `targetApiVersion` / `staticScope` / `exceptionMode` | `app/app/src/main/resources/META-INF/xposed/module.prop:1-4` |
| Declared scope (6 packages) | `scope.list` | `app/app/src/main/resources/META-INF/xposed/scope.list:1-6` |
| App process entry | `Application` + `XposedServiceHelper.OnServiceListener` | `app/app/src/main/kotlin/top/honorhook/ui/HonorHookApp.kt:11` (wired at `app/app/src/main/AndroidManifest.xml:27`) |
| UI entry (single Activity, 3 tabs) | `ComponentActivity` | `app/app/src/main/kotlin/top/honorhook/ui/MainActivity.kt:61` (manifest `:47-56`) |
| Legacy framework markers | `meta-data xposedmodule` / `xposeddescription` / `xposedminversion` | `app/app/src/main/AndroidManifest.xml:36-44` |
| Mount-thread entry | `HandlerThread("HonorHook-mount")` | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountScheduler.kt:9` |
| Build/dev entry (only sanctioned) | `tools/dev.sh <subcommand>` | `tools/dev.sh:1` (~40 subcommands: `env`, `build`, `build-release`, `test`, `install`, `enable-module`, `deploy-release`, `decompile`, `jadx`, `log-tag`, `verify`, …) |

Note: source lives under `app/app/src/main/kotlin/` (not `java/`), and the xposed metadata under
`app/app/src/main/resources/META-INF/xposed/` (not `assets/`). There is **no** `res/layout/` — the whole UI
is Compose.

## Architectural Constraints

Hard rules from `CLAUDE.md` and the code that enforces them:

- **No reboot, ever, in any form.** Anything requiring a reboot to take effect must stop and hand back to
  the user (`CLAUDE.md:26`). Practically this is why the switch contract is "effective on next injection":
  the deliverable is *force-stop the target app*, never *restart the device*.
- **Real-device-only verification.** `abiFilters += "arm64-v8a"` drops x86/x86_64 emulator ABIs entirely
  (`app/app/build.gradle.kts:20-22`); the project's declared verification medium is a physical HONOR device.
- **`reference/` is read-only** — 10 third-party repos pinned as git submodules (`.gitmodules`), used as
  upstream reference for ported UI components and pattern provenance.
- **All paths must be ASCII/English** (`CLAUDE.md:22`); device I/O goes through `tools/dev.sh` (`CLAUDE.md:11`),
  never hand-written adb/gradle.
- **`packaging { merges += "META-INF/xposed/*"; excludes += "**" }`** (`app/app/build.gradle.kts:51-58`) is
  the hard guarantee that `java_init.list` survives minification. Touching proguard/packaging requires
  re-verifying release artifact metadata (`CLAUDE.md:40`).
- **Release = minify + shrinkResources + debug signing** (`app/app/build.gradle.kts:26-33`); `HookEntry`,
  its overrides, and `FeatureCatalog`/`RemoteConfig` are explicitly kept (`app/app/proguard-rules.pro:17-35`).
- **Static scope is a firewall, not config.** `TargetRegistry` targets and `scope.list` must agree, or
  `onPackageLoaded` never fires for that package (`TargetRegistry.kt:18`).
- **Package-visibility permission.** `QUERY_ALL_PACKAGES` is declared because API 30+ hides
  `com.exam8.chengrenGK` without it, which would break the target list icons
  (`app/app/src/main/AndroidManifest.xml:10-14`).
- **`minSdk 26` but `miuix-blur` needs 33.** Guarded by `enableBlurSdk` (`MainActivity.kt:93-94`) plus
  `tools:overrideLibrary` (`AndroidManifest.xml:8`) — API-gated, never a blind call.
- **Abandoned decisions are frozen.** `system_server` guarding (settings) is documented as
  "do not re-evaluate" (`docs/hook-inventory-audit.md:14`) — libxposed api 102 has no
  `onSystemServerLoaded` callback.

## Anti-Patterns

Observed in the codebase or forbidden by `CLAUDE.md`'s discipline rules:

1. **Refactoring to compress line count.** Explicitly banned: comments are the only audit trail, vendored
   files must stay aligned with upstream, and measured compressible space is only 2–4 %
   (`CLAUDE.md:19`, `docs/ui-miuix-overview.md:29-35`). The line-count-neutral refactors
   ("extract `MiuixPage` from 5 copies of `Scaffold + TopAppBar + insets`") buy consistency, not lines, and
   are explicitly discouraged.
2. **Guessing an obfuscated class name without a content anchor.** `MethodResolver.kt:93-99` skips the
   DexKit fallback for default-package classes with no anchor — "avoid whole-APK false hits". Honour the
   rule: no anchor ⇒ honest `NOT_RESOLVED`, never a hardcoded guess (`CLAUDE.md:43`).
3. **Reading a config into a process that isn't injected.** A switch in the UI does nothing unless the
   package is both in `scope.list` and registered in `TargetRegistry`; mismatch = silent no-op
   (`TargetRegistry.kt:18`).
4. **Hooking a "gate" instead of the arbiter.** Shorting `isBuyBtnShow` to false aborts the downstream
   update that the UI actually depends on (`docs/hook-craft.md:15`) — the documented cause of the
   "hooked but nothing changed" class of bug.
5. **Hooking a consumer when a data source is reachable** (violates the root-principle; e.g.
   `theme:ItemInfo.isVipResouce` was moved to `getVipOnly`, `docs/hook-inventory-audit.md:22`).
6. **Blocking a lifecycle method without `proceed`.** `FINISH_ACTIVITY` deliberately proceeds first then
   finishes, because a non-proceeded `onCreate` leaves later lifecycle callbacks running on an uninitialized
   Activity (`ThemeStoreHook.kt:119-131`).
7. **Mutating a shared argument in place.** `SystemUiHook` passes a filtered *copy* because with no VSIM the
   host passes the upstream Flow's own list straight through (`SystemUiHook.kt:21-23`, `:108`). Same rule for
   forced collection values, which are rebuilt per call (`InstallerHook.kt:331-334`).
8. **Trusting jadx output as ground truth for method existence.** Existence must be confirmed against the
   on-device APK's dex (`docs/hook-inventory-audit.md:42`); the `getIsVipUser` miss is the recorded case.
9. **Declaring success on a write that may not have landed.** Fixed by `FeatureStore.setEnabled` returning
   the downstream result (`FeatureStore.kt:52`, `docs/ui-miuix-overview.md:10-12`); reverting to a bare
   `Unit` would re-introduce a false-feeling UI.
10. **Reading `SharedPreferences` directly from a composable.** It is not snapshot state, so counters freeze
    (`FeatureStore.kt:26-32`).
11. **Shipping a dead mount point.** Zero consumer = zero effect; deletion requires a real-device A/B, not
    static reasoning (`docs/hook-inventory-audit.md:20`, `docs/hook-craft.md:24`).
12. **Doing work inside a hot intercept.** Blocking IO / complex computation inside a hook callback violates
    minimal invasion; payloads are pruned via bounded table lookups and cached reflection
    (`ThemeStoreParts.kt:200-212`).
13. **Scope creep on an existing task** — out-of-list refactors, opportunistic bug fixes, and dead-code
    deletion inside an unrelated change are forbidden (`CLAUDE.md:18`).
14. **An `installed N/M` line that hides a silent no-op.** `SystemUiHook.warnUnreadableOnce`
    (`SystemUiHook.kt:113-120`) exists precisely because "1/1 installed" plus "nothing happened" is the
    hardest failure to diagnose.

## Error Handling

**Failure grading** — defined at `MountEngine.kt:12` and consumed in `MountEngine.kt:80-99`:

| Grade | Meaning | Action | Site |
|---|---|---|---|
| `OK` | method resolved and hook registered | count up | `MountEngine.kt:81` |
| `FAILED` | registration threw; **retry is meaningless** | WARN + record in `failedIds`, siblings continue | `MountEngine.kt:82`, `:160` |
| `NOT_RESOLVED` | class/method/value not loaded yet; **retry on class load** | collected and re-attempted | `MountEngine.kt:83`, `:102` |

Retry budget: 2 rounds at 500 ms then 2000 ms (`MountEngine.kt:206-208`). After exhaustion the unresolved
tasks are appended to `failedIds` and a WARN line lists them (`MountEngine.kt:91-96`) — so the summary
always ends with an explicit failure list rather than a quiet shortfall.

**Layers of protection, outermost first:**

1. `HookEntry.kt:25-34` — the entire dispatch is wrapped in `runCatching`; any target-install throw becomes
   `target install failed for <pkg>` at ERROR. The host process is never crashed.
2. `mountExceptionMode(PROTECTIVE)` at `MountEngine.kt:154` (and `ThemeStoreHook.kt:65`) — an exception
   thrown *inside* an interceptor cannot propagate into the host.
3. `MethodResolver.resolveMethod` — direct-reflect failures log and fall through to DexKit
   (`MethodResolver.kt:77-79`); DexKit failures return null and degrade to `NOT_RESOLVED`
   (`DexKitHelper.kt:58-63`). A DexKit failure never breaks the direct track.
4. `DexKitHelper.ensureDexKitLoaded` tries `System.loadLibrary("dexkit")` exactly once; failure disables the
   fallback track entirely without touching the primary track (`DexKitHelper.kt:32-43`).
5. **Fail-open inside interceptors**: every bespoke intercept returns `chain.proceed()` when its own
   preparation fails — installer bypass (`InstallerHook.kt:368`, `:389`), slot prune
   (`CollectorsSource.kt:148-150`), wallpaper path fix, tab prune. The module degrades to "no effect", never
   to "broken host".
6. **Guarded DOM reads**: `SystemUiHook.kt:88` wraps the `List` cast, `:95` the `slotId` field read;
   unreadable fields increment a counter that produces a one-shot WARN (`SystemUiHook.kt:113-120`) instead of
   a silent no-op.
7. **UI-side**: `FeatureStore.setEnabled` returns `false` on both the no-binder and write-exception paths
   (`FeatureStore.kt:55-66`); `FrameworkState.publish` falls back to `API_102` if reading metadata throws
   (`FrameworkState.kt:61-64`); target-list icon loading degrades to an initial-letter chip
   (`FeaturesScreen.kt:235-259`).
8. **Never-crash contract for the framework itself**: `module.prop:4` sets `exceptionMode=protective` globally.

## Cross-Cutting Concerns

- **Observability / judging.** One summary line per target, `$hookName: installed N/M`, emitted at INFO or
  WARN, never gated by `DEBUG_LOGGING` (`MountEngine.kt:129-137`). Per-task "effect triggered" logs are
  throttled to 2 hits per task (`MountEngine.kt:211`, `MountEngine.kt:171-177`) and never gated. Cloud/slot/tab
  prunes have their own throttled loggers (`ThemeStoreParts.kt:220,230,241`). Logs go to the **LSPosed module
  log** `/data/adb/lspd/log/modules_*.log`, not logcat (`README.md:54`).
- **Version-drift defense.** Per-target `HOST_VERSION_BASELINE` (`ThemeStoreHook.kt:164`,
  `InstallerHook.kt:69`, `Exam8Hook.kt:69`, `SettingsHook.kt:47`, `LauncherCardTitleHook.kt:42`,
  `SystemUiHook.kt:39`) is checked once per process via `ActivityThread.currentApplication`
  (`MountEngine.kt:183-202`); a mismatch emits WARN and re-points the reader at `installed N/M`.
- **Configuration.** Exactly two SP files, both UI-side: `honorhook_features` (`FeatureStore.kt:18`) and
  `honorhook_settings` (`AppSettings.kt:53`). The hook side never opens local SP — it only reads the remote
  group.
- **Security / permission posture.** `allowBackup=false` (`AndroidManifest.xml:28`); single exported Activity
  with `singleTask` and a LAUNCHER intent filter; no network permission declared at all; no credential,
  keystore, or `local.properties` file is tracked in git (`.gitignore` excludes `local.properties`,
  `app/app/build/`, `.buildtmp/`).
- **Internationalization.** All user-facing strings are hardcoded Chinese literals in Kotlin
  (`FeatureCatalog.kt:42-81`, screens); the only `res/values/strings.xml` entry consumed by the framework is
  `module_description` (`AndroidManifest.xml:29`). Module name/description must stay `@string` references —
  aapt2 rejects literals in `android:description` (`AndroidManifest.xml:22-25`).
- **Testing.** Only the 4 decision tables are covered, and they are covered precisely because they are the
  only tables whose mutation silently changes online behaviour (`DecisionTablesTest.kt:12-16`). Everything
  else requires a real device.
- **Documentation policy.** `docs/` records only what the code cannot show: reverse-derived facts,
  rationale, external constraints, and judgement criteria. Class lists, call chains, and mount-point tables
  live in code (`docs/README.md:5`). Rationale strings therefore live as KDoc on the constants themselves
  (`InstallerConfigTable.kt:9-13`, `BottomTabFilter.kt:32-38`).

---

*Architecture analysis: 2026-10-11*
