<!-- 归档：docs/archive/hc-hook-structure.md → skill references（2026-10-10 md 重构） -->
<!-- 口径提示：正文写于 Windows 期/早期调研阶段，Windows 路径（C:\ / .bat / tools/jdk-21）与已删目录（.buildtmp/、system-files/*-apktool 等）可能失效；结论与方法论仍有效。 -->

# HyperChanger（HC）hook 工程组织分析 — HonorHook 拼装模板

> 分析对象：`D:\project\lsposed-plugin\reference\HyperChanger（同类插件）`（HyperOS 4 控制中心/通知栏/锁屏/相机定制模块，libxposed API 102，minSdk 35，release 已混淆但保留 java_init.list 硬编码类名）。
> 用途：作为 HonorHook hook 入口（多目标系统组件 hook）的直接模板。所有结论附 file:line 证据。
> 配套框架源码：`D:\project\lsposed-plugin\reference\LSPosed（框架源码）`（daemon + core，API 102 对应实现）。

## 0. 五个调研目标速览

| # | 目标 | 结论 |
|---|------|------|
| 1 | 入口与分发 | `HyperSystemUiModule : XposedModule()` 单类承载全部 systemui/aod/settings 目标；分发 = 包名 if/return 前置过滤 + `when (packageName)` 主分发 + 每个 hook 一组 `xxxInstalled` 布尔防重装。system_server 走 `onSystemServerStarting` 单独入口 |
| 2 | hook 类组织 | 双模式：95% hook **内联在 HyperSystemUiModule 方法中**（14,560 行单文件），按功能拆 `installXxxHook(loader, prefs)` 私有方法；独立工程目标（system_server/相机/设置/更新/录屏/乐水印锁屏）拆成 `object/class : XposedModule()` 单独文件。典型写法：`hook(method).setExceptionMode().setId().intercept{…}`，配置**每次 intercept 现读** |
| 3 | 配置通信 | 双通道：**标量配置**走 libxposed 远程 SharedPreferences（UI 进程经 `XposedService.getRemotePreferences` 读写 → LSPosed daemon SQLite 持久化 → binder 推送给所有 hook 进程，hook 侧只读 + `registerOnSharedPreferenceChangeListener` 热监听）；**二进制资产**（自定义图片/视频）走 exported ContentProvider（`SettingsAppearanceProvider`）query/openFile 直读模块 filesDir。`DockMaterialProvider` 是反向通道（system_server `call()` 模块进程建 GL 视图回传 SurfacePackage） |
| 4 | 注册文件 | `java_init.list` 6 个入口类（主模块 + 5 个独立 module），每类独立 `onPackageLoaded` 按包过滤；`module.prop` 5 个键；`scope.list` 25 行（含 `system` 特殊域）；`module.prop` 5 键；另有 `native_init.list`（libhyperpatch.so）。`app/build.gradle.kts` 需 `packaging.resources.merges += "META-INF/xposed/*"` |
| 5 | autoHotReload=true | 由 LSPosed 框架 Manager 侧消费（本仓库 daemon 源码 grep 无此字段）；语义 = 模块**代码更新后**框架自动热重载注入新 dex 到作用域进程，免手动杀进程/重启；与"配置热生效"（远程 prefs 监听，HC 运行期实现）是两回事 |
| 附 | 热生效链路 | UI toggle 变更 → `update()` 双写本地 SP + 远程 prefs → daemon SQLite 落库 → `onUpdateRemotePreferences` binder 推送 → hook 进程 listener 回调 → 部分 hook 直接重画（免重启）；未被监听的项靠拦截器每次现读配置自然生效 |

---

## 1. 入口与分发

### 1.1 入口类与回调

```kotlin
// HyperSystemUiModule.kt:295-302
class HyperSystemUiModule : XposedModule() {
    internal fun installHook(member: java.lang.reflect.Executable) = hook(member)   // L296: 暴露给独立 hook 类用的统一挂载口

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        android.util.Log.i("HyperChangerDock", "system server callback entered")
        runCatching { DockWmsHook.install(this, param.classLoader) }
            .onFailure { error -> log(Log.ERROR, TAG, "Could not install desktop Dock WMS hook", error) }
    }
```

- system_server 钩子（Dock WMS）走 `onSystemServerStarting`（L298-302），**且** scope.list 里配了 `android` 使 `onPackageLoaded(param.packageName == "android"/"system")` 也会触发 DockWmsHook（L624-627）——双保险。
- 应用进程钩子全走 `onPackageLoaded`（L622 起）。

### 1.2 onPackageLoaded 分发结构（L622-921）

分发是**四级过滤 + when 主分发**，不是 Map：

```kotlin
// HyperSystemUiModule.kt:622-657
override fun onPackageLoaded(param: PackageLoadedParam) {
    if (!OsCompatibility.areHooksAllowed()) return                      // L623 一票否决（机型/系统版本门控）
    if (param.packageName == "android" || param.packageName == "system") {   // L624 ① system_server / SystemUI 特殊路径
        runCatching { DockWmsHook.install(this, param) }...; return
    }
    if (param.packageName == "com.miui.home") { ...syncHomeRecentsNativeConfig...; return }  // L629 ② 桌面
    if (param.packageName == "com.xiaomi.shop" || ... 7 个三方应用) { installAppNavigation(param); return }  // L638 ③ 应用导航毛玻璃（独立分发函数 L309-356，内部再 when(packageName) 到 AppBottomNavHooks.*）
    if (param.packageName == SETTINGS_PACKAGE) { ...installSettingsAppEntryHook...; return }   // L646 ④ 设置入口
    if (param.packageName == LOCKSCREEN_WALLPAPER) { /* WallpaperProbe 由 java_init.list 的 Main.java 独立负责，此处故意空 */ return }  // L652
    if (param.packageName !in SYSTEM_UI_TARGETS) return                 // L657 ⑤ 集合过滤（8 目标见 L13827）
    if (param.packageName == SUPER_XIAOAI_IME || ...) { installSuperXiaoAiHooks(...); return }
    runCatching {                                                        // L662 ⑥ 主分发 + 整体异常防护
        val preferences = getRemotePreferences(REMOTE_PREFERENCE_GROUP) // L663 拉远程配置（每回调拿一次）
        when (param.packageName) {                                       // L664 主分发
            SYSTEM_UI, SYSTEM_UI_PLUGIN -> { ...60+ 个 installXxxHook 顺序调用... }   // L665-850
            AOD -> { ... }                                               // L851
            SUBSCREEN_CENTER -> { ... }                                  // L879
            PERSONAL_ASSISTANT -> { ... }                                // L886
            THEME_MANAGER -> { ... }                                     // L895
            else -> return
        }
    }.onFailure { error -> log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error) }
}
```

- **每目标 = 方法调用**：`installControlCenterEditButtonHook(loader, prefs)`、`installLockscreenNotificationHook(loader, prefs)`… 全部是模块实例私有方法（同文件），hook 注册都在这些方法内部。
- **防重装 guard**：SystemUI 与 `miui.systemui.plugin` 两个包回调都会进同一分支（插件常被 SystemUI 类加载器合并加载，包回调不保证独立触发——见 L729-734 注释），因此每个 hook 配 `private var xxxHookInstalled = false` 布尔（L304-307 声明，L674/679/696… 逐处判装）。systemui+plugin 重叠安装同一方法会重复挂载，guard 是硬要求。
- **窗口/重试机制**：类可能还没加载，`installCustomTileHooks` 找不到 `MiuiQSFactory` 时 postDelayed 1.5s 自重试（L408-416）。
- **SysUI 目标常量**（L13804-13827 companion object）：

```kotlin
private const val SYSTEM_UI = "com.android.systemui"
private const val SYSTEM_UI_PLUGIN = "miui.systemui.plugin"
private const val AOD = "com.miui.aod"
private const val SUPER_XIAOAI_IME = "com.xiaomi.type"
...
private val SYSTEM_UI_TARGETS = setOf(SYSTEM_UI, SYSTEM_UI_PLUGIN, AOD, SUPER_XIAOAI_IME, SUPER_XIAOAI_PHRASE, SUBSCREEN_CENTER, PERSONAL_ASSISTANT, THEME_MANAGER)  // L13827
```

---

## 2. hook 类组织

### 2.1 组织模式：单文件巨类 + 少数独立类

| 组织方式 | 文件 | 内容 |
|---|---|---|
| 内联私有方法（主模式） | `systemuihook/HyperSystemUiModule.kt`（14,560 行） | SystemUI/plugin/AOD/设置入口/桌面/三方应用约 120 个 hook，全部为 `private fun installXxxHook(classLoader, preferences)` + `private var xxxInstalled` 布尔 |
| 独立 object（同包） | `systemuihook/DockWmsHook.kt` | system_server WMS 桌面 Dock：`object DockWmsHook { fun install(module, param/loader) }` |
| 独立 XposedModule 子类（同包） | `SettingsDeviceModule.kt` / `SystemUpdateModule.kt` / `ScreenRecorderModule.kt` | 单目标包专用入口（com.android.settings / com.android.updater / com.miui.screenrecorder），各自 `onPackageLoaded` 里 `if (param.packageName != X) return` |
| 独立入口类（异包） | `leicaunlocker/hook/LeicaUnlockHook.java`（Java，相机/图库乐水印） | `public final class LeicaUnlockHook extends XposedModule`，`isTargetPackage()` 判定（L238-242） |
| 独立子包组件 | `systemuihook/newnotificationcenter/*`（iOS 通知中心 5 个 Java 类）、`systemuihook/hypermusiccover/*`（Java 移植的乐水印锁屏，入口 `Main.java`） | 整块移植功能包，含自研 View/动画类 |
| UI 侧对象 | `SettingsAppearanceApplier.kt`、`SettingsAppearanceSource.kt`、`HookSettings.kt`（settings 数据类 + store） | 被 SystemUI/设置进程里的 hook 复用（module 类在 hook 进程中也是模块 APK 的类，直接静态引用的另一分类） |

**共享设施**：无 hook 工具基类，只有 `HyperSystemUiModule.installHook(member)`（L296）转发到 `XposedModule.hook()`，方便独立类拿 module 实例统一挂载；配置读取靠 `REMOTE_PREFERENCE_GROUP` 常量（`HookSettings.kt:10`）+ 顶层扩展函数（`readDockSettings`、`ScopedSettings.getBoolean…`）。

### 2.2 典型 hook 类完整写法 — DockWmsHook（system_server 目标）

```kotlin
// DockWmsHook.kt:21-92（节选）
internal object DockWmsHook {
    fun install(module: HyperSystemUiModule, param: PackageLoadedParam) = install(module, param.defaultClassLoader)   // L63
    fun install(module: HyperSystemUiModule, loader: ClassLoader) {
        val windowClass = runCatching { loader.loadClass("com.android.server.wm.WindowState") }.getOrNull() ?: return
        val prepare = windowClass.declaredMethods.firstOrNull {
            it.name == "prepareSurfaces" && it.parameterCount == 0
        } ?: return
        module.installHook(prepare)                                            // 经模块统一口挂载
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)        // 拦截器抛异常不炸宿主
            .setId("hyperchanger:desktop-dock-wms")                            // 幂等 ID（框架用它防重复/日志标识）
            .intercept { chain ->
                val result = chain.proceed()
                runCatching { update(chain.thisObject, module) }.onFailure { ... }
                result
            }
        ...
        installRevealHook(module, loader)   // 第二个目标类 KeyguardController/ActivityTaskManagerService.keyguardGoingAway（L94-111）
    }
```

**配置判断模式（重点）— 拦截器内现读远程 prefs**：

```kotlin
// DockWmsHook.kt:113-115
private fun beginReveal(module: HyperSystemUiModule) {
    val settings = readDockSettings(module.getRemotePreferences(REMOTE_PREFERENCE_GROUP))  // 每次触发都现读，无缓存
    if (!settings.enabled || !settings.entryAnimation) return
```

`readDockSettings`（DockSettings.kt:95-113）是纯读函数：`prefs.getBoolean(KEY_DOCK_ENABLED, false)·getInt(KEY_DOCK_MATERIAL, 1)·…`。收益：**开关变化下一次绘制即生效，不需要重启进程**；代价：每次回调一次 binder IPC（daemon 缓存了快照 map，LSPosedRemotePreferences 构造时一次拉全量，本地 ConcurrentHashMap 读，LSPosedRemotePreferences.java:22-55）。

### 2.3 主模块内联 hook 的典型形态（SysUI 锁屏/通知类）

```kotlin
// HyperSystemUiModule.kt:736-738（onPackageLoaded 内）
if (param.packageName == SYSTEM_UI && !lockscreenNotificationHookInstalled) {
    installLockscreenNotificationHook(param.defaultClassLoader, preferences)
    lockscreenNotificationHookInstalled = true
}
```

内部与 DockWmsHook 同构：`loadClass` → 找 Method → `hook(m)` → `setExceptionMode/setId` → `intercept { chain -> 读取 prefs 判定 → 改参/改结果/加副作用 }`。拦截器内判定示例（AOD 编辑背景，L12903-12929）：`val mode = preferences.getInt(KEY_LOCKSCREEN_EDITOR_BACKGROUND_MODE, LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM); if (mode == …SYSTEM) return@intercept result` —— 配置判断永远放 intercept 内现读，不缓存进静态变量（除少量 `@Volatile` 持有 controller 实例，L13809）。

---

## 3. 配置通信机制（核心章节）

### 3.1 总体架构：双通道

```
┌─ UI 进程（模块自身，btm.m.os4.systemuihook）─────────────┐
│  HookApplication (XposedServiceHelper.OnServiceListener)  │  绑定 LSPosed daemon 注入的本地服务
│  MainActivity/设置页 → HookSettingsStore.update()         │
│     ├─ local SP 双写（模块自己 data 目录，防 daemon 不可用时回退）│
│     └─ service.getRemotePreferences(GROUP).edit().apply() │──▶ LSPModuleService.updateRemotePreferences (binder)
└──────────────────────────────────────────────────────────┘
                              │
                              ▼
        ┌─ LSPosed daemon（lspd，system 域进程）────────────┐
        │  SQLite `configs` 表：(module_pkg_name, user_id,  │  ConfigManager.updateModulePrefs L475-505
        │  `group`, `key`, data=SerializationUtils 序列化)   │
        │  updateRemotePreferences 成功后:                  │
        │  onUpdateRemotePreferences(DIFF) ──binder 推送──▶ │  LSPInjectedModuleService L79-88
        └──────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
   hook 进程（systemui/aod/settings/…）    新请求进程
   LSPosedContext.getRemotePreferences        requestRemotePreferences(group, callback)
   = LSPosedRemotePreferences                 → 返回全量 map 快照 + 注册 callback
   （本地缓存 ConcurrentHashMap +            LSPosedContext.java:300-312
    registerOnSharedPreferenceChangeListener 收到 DIFF 推送即改缓存并回调）
   LSPosedRemotePreferences.java:20-129
```

二进制资产（图片/视频/歌词）不走 prefs——走 **exported ContentProvider**（见 3.5）。

### 3.2 UI 侧：服务获取 + 双写存储

**获取 XposedService**（`HookApplication.kt:11-29` 完整摘录）：

```kotlin
class HookApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() { super.onCreate(); XposedServiceHelper.registerListener(this) }
    override fun onServiceBind(service: XposedService) { serviceState.value = service }
    override fun onServiceDied(service: XposedService) { if (serviceState.value === service) serviceState.value = null }
    companion object { private val serviceState = MutableStateFlow<XposedService?>(null); val service = serviceState.asStateFlow() }
}
```

**存储封装——双写模式**（`HookSettings.kt:342-397` + `DockSettings.kt:47-93`，两个 Store 完全同构）：

```kotlin
// DockSettings.kt:57-68
fun syncRemote(service: XposedService) {
    val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
    settings = if (remote.contains(KEY_DOCK_ENABLED)) read(remote) else settings   // 远程先行：daemon 数据为权威
    write(local, settings)
    write(remote, settings)                                                        // 双向对齐
}
fun update(service: XposedService?, transform: (DockSettings) -> DockSettings) {
    settings = transform(settings)
    write(local, settings)                                          // ① 本地先写（无 service 也能存，UI 自洽）
    service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }  // ② 远程推送
}
```

- 偏好分组名常量：`const val REMOTE_PREFERENCE_GROUP = "hyper_system_ui_hook"`（HookSettings.kt:10）。
- 序列化：纯 XML/SP 原生类型（bool/int/float/String）。**复合结构**（MaterialOverride 18 字段）用 `|` 分隔的单字符串（`parseMaterialOverride` HookSettings.kt:1291-1315），JSON 仅用于用户预设导入导出（`exportShadePreset` L1323）。**禁止自定义 Parcelable/Serializable**（binder 跨进程限制）。
- UI 侧写入的完整路径：`service.getRemotePreferences()` 返回可写实现 → `LSPModuleService.updateRemotePreferences(group, diff)`（daemon 侧 LSPModuleService.java:200-225）→ SQLite 落库 → 推 diff。

### 3.3 框架链路证据（LSPosed 源码，API 102）

**hook 侧读**（`core/.../LSPosedContext.java:300-312`）：

```java
@NonNull @Override
public SharedPreferences getRemotePreferences(String name) {
    if (name == null) throw new IllegalArgumentException("name must not be null");
    return mRemotePrefs.computeIfAbsent(name, n -> {
        try { return new LSPosedRemotePreferences(service, n); }   // service = 注入到本进程的 ILSPInjectedModuleService
        catch (RemoteException e) { ... throw new XposedFrameworkError(e); }
    });
}
```

`LSPosedRemotePreferences`（`core/.../LSPosedRemotePreferences.java:20-129`）关键性质：
- **只读**：`edit()` 直接 `throw new UnsupportedOperationException("Read only implementation")`（L112-114）——hook 进程不能写配置。
- **快照 + 推送**：构造函数 `service.requestRemotePreferences(group, callback)` 一次拉全量到本地 `ConcurrentHashMap mMap`（L50-55）；daemon 侧注册 callback（LSPInjectedModuleService.java:42-49，linkToDeath 自动清理）。
- **热监听**：`registerOnSharedPreferenceChangeListener`（L117-121）由 daemon 推送触发：

```java
// LSPosedRemotePreferences.java:26-48
IRemotePreferenceCallback callback = new IRemotePreferenceCallback.Stub() {
    @Override synchronized public void onUpdate(Bundle bundle) {
        Set<String> changes = new ArraySet<>();
        if (bundle.containsKey("delete")) { ... mMap.remove(key) ... }
        if (bundle.containsKey("put"))    { ... mMap.putAll(puts) ... }
        synchronized (mListeners) { for (var key : changes) mListeners.forEach(l -> l.onSharedPreferenceChanged(..., key)); }
    }
};
```

**daemon 侧写 + 推送**（`daemon/.../LSPModuleService.java:200-225`）：

```java
public void updateRemotePreferences(String group, Bundle diff) {
    ...按 diff 的 delete/put 组装 values...
    ConfigManager.getInstance().updateModulePrefs(loadedModule.packageName, userId, group, values);  // SQLite 落库
    ((LSPInjectedModuleService) loadedModule.service).onUpdateRemotePreferences(group, diff);       // 推给所有已注册 hook 进程
}
```

**持久化**（`daemon/.../ConfigManager.java:475-518`）：存 **daemon 自己的 SQLite 数据库** `configs` 表（不是模块 data 目录的 XML！）：

```java
contents.put("`group`", group); contents.put("`key`", key);
contents.put("data", SerializationUtils.serialize((Serializable) value));   // L488 序列化对象直接入库
contents.put("module_pkg_name", moduleName); contents.put("user_id", ...);
db.insertWithOnConflict("configs", null, contents, SQLiteDatabase.CONFLICT_REPLACE);   // L491
```

另：`listRemoteFiles()/openRemoteFile()/deleteRemoteFile()`（LSPosedContext.java:316-334、LSPModuleService.java:234-267）提供模块 files 目录的跨进程文件通道（`XposedService.listRemoteFiles/openRemoteFile`），HC 未用（大文件走 ContentProvider 而不是这条——Provider 无需 root/无大小限制）。

### 3.4 hook 侧读取：现读 + 变化监听两个机制

1. **现读（默认）**：intercept 内 `preferences.getBoolean/getInt(...)`（远程 SP 本地缓存，开销极小），如 DockWmsHook.kt:114、L12909。**开关变更无需任何操作即生效**。
2. **变化监听（需要主动重画的场景）**：`registerOnSharedPreferenceChangeListener` 三处实例：
   - L945-974：乐水印锁屏状态桥（KEY_LOCKSCREEN_MUSIC_LOCKSCREEN_ENABLED 等变化时重新分派演示状态）；
   - L1305-1337：控制中心顶栏按钮（16 个 key 变化 → post 主线程重建按钮视图）；
   - L5889-5904：状态栏堆叠信号（KEY_MOBILE_NETWORK_TYPE_* 变化 → `refreshStackedMobilePresentations`）。
   - 回调线程 = daemon binder 线程，涉及 UI 必须 `Handler(Looper.getMainLooper()).post`（L1329）。
3. **二进制资产的"变化检测"**：Provider 返回 `size/modified` 元数据 + hook 侧 `cacheKey()`（全部字段拼串，SettingsAppearanceSource.kt:70）比对，变了才重建视图（SettingsAppearanceApplier.kt:68/113/181）。

### 3.5 SettingsAppearanceProvider：UI→hook 进程的二进制资产通道

**动机**：设置页自定义背景/Logo/设备图/锁屏编辑背景图等大文件不适合进 prefs。HC 在**自己的 UI 进程**里跑一个只读 ContentProvider，hook 进程（com.android.settings / com.miui.aod）通过 `content://btm.m.os4.systemuihook.settingsappearance/<slot>` 直读模块 filesDir 文件。

**Manifest 声明**（AndroidManifest.xml:62-70）：

```xml
<provider android:name=".SettingsAppearanceProvider"
    android:authorities="btm.m.os4.systemuihook.settingsappearance"
    android:exported="true" android:grantUriPermissions="true" />
<provider android:name=".DockMaterialProvider"
    android:authorities="btm.m.os4.systemuihook.dockmaterial"
    android:exported="true" />
```

**Provider 实现**（SettingsAppearanceProvider.kt）：
- `query()`（L18-92）：返回一行 MatrixCursor，列 = 全部 UI 配置标量（mime/size/modified/enabled/opacity/blur/font/scale/logo_mode/…+ 30 余个风格字段），值来自 `getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES="settings_appearance", 0)`（常量定义 SettingsAppearanceSettings.kt:9）+ 文件元数据。注释明说"MatrixCursor is read through Cursor.getInt() in the Settings process. Store a numeric flag so the value survives the provider IPC boundary"（L36-37）——**IPC 边界只保 int/String，布尔转 1/0**。
- `openFile()`（L98-109）：返回 `ParcelFileDescriptor.open(File(filesDir, "settings_appearance")/<slot>.bin, MODE_READ_ONLY)`——文件槽位白名单校验（L167-180），歌词库/锁屏编辑背景走独立路径（L169-174）。
- `insert/delete/update` 全部 no-op/null（L111-113）——只读通道，写入仍在 UI 侧 SP+文件。
- 特殊能力：lockscreen_schedule 槽实时查 CalendarContract 返回"下一个日程"（L132-165）；光度模式资产切换（color_mode_assets_enabled + isNight()，L176-178）。

**hook 侧消费**：`SettingsAppearanceSources`（SettingsAppearanceSource.kt:85-215）单例，**750ms TTL 内存缓存**避免每个资源回调都做 Provider IPC（L91 `CACHE_TTL_MS = 750L`，L102-175 query + L177-184 invalidate）；消费方 `SettingsAppearanceApplier`（systemuihook/SettingsAppearanceApplier.kt:26-1160，在 com.android.settings 进程内）把原设备页背景藏掉、插入自绘 `SettingsBackgroundView`、替换 Logo Drawable、重建教程卡/设备卡视图（L49-129），视图会话按 Activity/Fragment 用 WeakHashMap 管理存续与恢复（L26-41）。

**SystemUI/AOD 侧直读文件**（com.miui.aod 的 EditorActivity 进程里），HyperSystemUiModule.kt:12921-12926：

```kotlin
activity.contentResolver.openFileDescriptor(
    android.net.Uri.parse("content://btm.m.os4.systemuihook.settingsappearance/${SettingsAppearanceProvider.LOCKSCREEN_EDITOR_BACKGROUND_SLOT}"),
    "r")?.use { descriptor -> BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor) }
```

### 3.6 DockMaterialProvider：反向 RPC（hook 进程 → UI 进程建 GL 视图）

和配置无关，但同属 Provider 通道的成熟用法，供参考：system_server 里无法用 `SurfaceControlViewHost` 创建硬件加速的 MIUI 玻璃 View（vendor API 限制），HC 让**模块 UI 进程**来建视图，再经 `call()` 把 `SurfacePackage` 传给 system_server 挂进 WMS 窗口树：

- `DockMaterialProvider.call(method, arg, extras)`（DockMaterialProvider.kt:46-82）：`create`（建 SurfaceControlViewHost + 玻璃配置，`result.get(4, TimeUnit.SECONDS)` 阻塞等待首帧回调 L78-79）/ `probe`（查纹理就绪）/ `release`。
- UID 校验（L47-49）：`Binder.getCallingUid() != SYSTEM_UID && != myUid()` 直接拒绝——**跨 uid 的 Provider 必须鉴权**。
- 主线程限制：所有 SurfaceControlViewHost 操作 post 到模块 UI 进程主 Handler（L33/53）。
- 系统侧消费：DockWmsHook 第 175 行附近经 `context.contentResolver.call(...)` 调用（surface 挂到 WindowState 的 SurfaceControl 下）。

### 3.7 全链路示例（用户切一个开关 → 界面变化）

1. 用户在设置页改"锁屏歌词" → `HookSettingsStore.update(service) { it.copy(...) }` 双写（HookSettings.kt:371-375）。
2. daemon 落 SQLite + binder 推送 diff（LSPModuleService.java:220-221）。
3. SystemUI 进程内 `LSPosedRemotePreferences.onUpdate` 改本地缓存并回调 listener（LSPosedRemotePreferences.java:27-47）。
4. HyperSystemUiModule L945 监听器收到 KEY_LOCKSCREEN_MUSIC_LYRICS_ENABLED → 调 `syncHyperMusicCoverLyrics(preferences)` 直接重应用（无需重启）。
5. 未监听项（如 Dock 高度）→ 下次 `prepareSurfaces` 拦截 `readDockSettings` 现读即生效。

---

## 4. 注册文件内容与多入口组织

### 4.1 java_init.list（6 个入口类，逐行）

```
btm.m.os4.systemuihook.HyperSystemUiModule          ← 主入口：systemui/plugin/aod/设置入口/桌面/三方应用导航（120+ hook）
btm.m.os4.systemuihook.hypermusiccover.Main         ← Java 移植的"乐水印锁屏"（独立包 + miwallpaper GL 上传钩子，见 HyperSystemUiModule.kt:652-656 注释 "WallpaperProbe is registered separately through java_init.list and owns this process's GL upload hook"）
btm.m.leicaunlocker.hook.LeicaUnlockHook            ← 相机/图库乐水印解锁（LeicaUnlockHook.java:143 onPackageLoaded，isTargetPackage L238-242）
btm.m.os4.systemuihook.SettingsDeviceModule         ← com.android.settings 设备页专用（SettingsDeviceModule.kt:34-36 起）
btm.m.os4.systemuihook.SystemUpdateModule           ← com.android.updater 更新页专用（SystemUpdateModule.kt:16-18）
btm.m.os4.systemuihook.ScreenRecorderModule         ← com.miui.screenrecorder 录屏专用（ScreenRecorderModule.kt:20-21）
```

**多入口组织意义**：
- 每个入口都是独立 `XposedModule` 子类，各自 `onPackageLoaded` 按包过滤 → 多个入口可以**命中同一进程**（如 com.android.settings 同时命中 HyperSystemUiModule L646 与 SettingsDeviceModule），框架会构造所有入口实例并向其派发回调。
- 职责边界 = **大而全主入口 + 每包一个小而专入口**：主模块分类内联，跨功能块/异包老代码（leicaunlocker、hypermusiccover）保持独立入口，便于独立演进、避免污染主分发。
- libxposed 入口按 **META-INF/xposed/java_init.list 里的硬编码字符串**反射加载（LSPFRIDA 注释也确认此事，见 LSPFRIDA/app/proguard-rules.pro:13）——所以 release R8 混淆**不能混淆这 6 个类名**，proguard 需 keep（HC 的 proguard-rules.pro 与 LSPFRIDA 同法）。

### 4.2 module.prop

```
minApiVersion=101          ← 声明最低框架 API（101=XposedInterface API 1.10 线）
targetApiVersion=102       ← 目标框架 API（102 才有 remote prefs/callback、hotReloadModule 等新 API）
staticScope=true           ← 作用域"静态"模式：直接读 scope.list，不需要模块运行时用 requestScope 动态申请
autoHotReload=true         ← 声明模块**代码更新后**允许框架自动热重载（见 §5）
description=HyperChanger for HyperOS 4 control center, notification shade, lock screen, and camera customization.
```

（对比：LSPFRIDA 与 notification-guard 均 `autoHotReload=false`；LSPFRIDA 还用了 `staticScope=false` + `exceptionMode=protective`。）

### 4.3 scope.list（25 行作用域，全文）

```
com.android.systemui      system          com.miui.home         com.miui.miwallpaper
miui.systemui.plugin      com.miui.aod    com.android.camera    com.miui.gallery
com.hyper.gallery.plugin  com.miui.mediaeditor  com.miui.screenrecorder
com.xiaomi.type           com.miui.phrase com.android.settings  com.android.updater
com.xiaomi.subscreencenter com.miui.personalassistant com.android.thememanager
com.xiaomi.shop           com.mipay.wallet  com.mi.health       com.apple.android.music
com.sina.weibo            com.xingin.xhs  com.lucky.luckyclient
```

注意：`android`=system_server、`system`=SystemUI 是 LSPosed 特殊作用域名，对应 `onSystemServerStarting` 与 `onPackageLoaded("android"/"system")`（§1.2）。

### 4.4 native_init.list 与构建装配

- `native_init.list`：仅一行 `libhyperpatch.so`（native 启动注入入口，arm64-v8a 下有对应 so）。
- **APK 打包**（app/build.gradle.kts:61-66）：

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }
}
```

`merges` 保证 META-INF/xposed 不被 AGP/R8 剥掉（libxposed 官方要求的打包姿势）；不写这条时 src/main/resources 里的注册文件可能丢失，模块无法被框架识别。LSPFRIDA/notification-guard 无 packaging 块也能注册（文件在 src/main/resources 默认进包），此配置是为 R8 + 依赖合并双保险。

---

## 5. autoHotReload=true 的语义

**本地可验证的事实**：
1. HC `module.prop` 声明 `autoHotReload=true`；LSPFRIDA、notification-guard 均显式 `false`。
2. LSPosed 框架 daemon/core 源码（本仓库 reference）**全文 grep 无 autoHotReload 字段**——该 flag 不由框架运行时解析，是 **LSPosed Manager 应用侧**消费的声明（manager 仓库不在本地；本次会话外网不可达，未能抓取 manager 源码，以下语义按 LSPosed 1.10+ 生态文档口径，置信度=文档级、非代码级）。
3. 框架侧存在配套热重载能力：API 102 的 `XposedService.hotReloadModule(HookedTarget, Bundle, HotReloadCallback)`（LSPFRIDA 调研文档 `docs/Refactor-Upstream-Research.md:29/148` 引用 XposedService.java L319 实读确认；返回 HookedTarget.State=UP_TO_DATE/STALE/RELOADING/FAILED）；hook 侧 `LSPosedContext` 亦提供 `listRemoteFiles/openRemoteFile` 等重载辅助（LSPosedContext.java:314-334）。

**语义（文档级口径）**：`autoHotReload=true` 声明本模块**可以安全热重载**——当模块 APK 更新（或启用/作用域变更）时，Manager/框架自动把新模块 dex 重新注入其作用域进程（或自动重启受影响进程），**不再要求用户手动杀作用域进程或重启手机**。`false/缺省` = 模块更新后需用户手动操作才生效。HC 主战场是 SystemUI（重启 SystemUI 影响大），所以显式声明允许框架代劳；LSPFRIDA（进程外 Frida 注入）与 notification-guard（轻量）则保守关闭。

**两个"热"必须分清**：
- **配置热生效**（HC 工程内实现，§3）：远程 prefs 变化监听 + intercept 现读，改设置**永远不需要重启进程**——这是 HonorHook 必须照搬的；
- **代码热重载**（flag 声明的事）：模块**代码**更新后免手动重启——`autoHotReload=true` 只是对框架的许可声明，工程侧无需额外实现。

---

## 6. 对 HonorHook 工程的采纳建议

### 照搬（直接模板化）

1. **配置通道 = libxposed 远程 prefs**（§3.2-3.4）：`XposedServiceHelper.OnServiceListener`（HookApplication 模板）+ `syncRemote/update` 双写 Store（HookSettingsStore 模板）+ hook 侧 `getRemotePreferences(GROUP)` 现读 + 关键 UI 项注册 `registerOnSharedPreferenceChangeListener`。这是 HC 最成熟的骨架，HonorHook 的"UI 开关 ↔ hook 生效"链路 1:1 照搬。
   - 前提：框架支持 API 102（远程 prefs 依赖 lspd daemon SQLite 中转，老框架没有）——HonorHook 的 module.prop 应声明 `targetApiVersion=102` 并确认运行框架 ≥1.10。
2. **分发结构**（§1.2）：`if (pkg !in TARGETS) return` + `when(packageName)` + 每个 hook 一组 `installed` 布尔防重装 + 整体 `runCatching`。Honor 系统组件多目标（framework/SystemUI/设置/桌面……）直接套。
3. **单目标独立 hook 类**（§2.1-2.2）：一个目标一个 `object XxxHook { fun install(module, loader) }`，经 `module.installHook(member)` 统一口挂载，`setId("honor:xxx-yyy")` 幂等命名。
4. **配置判断放 intercept 内现读**（§2.3）：不缓存开关到静态变量，变化天然生效。
5. **静态资源通道 = exported 只读 Provider**（§3.5）：HonorHook 若需要自定义背景/Logo 图进设置页，用 `query()` 返回标量 + `openFile()` 返回 fd 的白名单槽位模式；**必须做 caller 鉴权**（HC 的 Dock 反例证明跨 uid call() 要验 UID，§3.6）。
6. **注册装配**（§4）：`java_init.list`（入口 keep 进 proguard）、`module.prop`（min/targetApiVersion + staticScope）、`scope.list`（含 `android`/`system` 特殊域）、`packaging.resources.merges += "META-INF/xposed/*"`。

### 简化（不照搬 HC 的"重"）

1. **不把 120+ hook 全塞一个入口文件**：HC 是单文件 14,560 行巨类（历史累积），HonorHook 按目标拆 `HonorFrameworkModule`（system_server）+ `HonorSystemUiModule` + `HonorSettingsModule` 等 2-4 个入口即可，分发逻辑同构但文件粒度健康。
2. **Provider 数量收敛**：HC 两个 provider 职责不同（一个资产通道、一个 GL RPC）。HonorHook 只需要资产通道；SurfaceControlViewHost 玻璃渲染属高端定制，首版不做。
3. **缓存策略可选**：HC 的 `SettingsAppearanceSources` 750ms TTL 缓存是为 com.android.settings 高频资源回调优化的；HonorHook 标量配置走远程 prefs 本身带 daemon 快照缓存，无需自建。
4. **autoHotReload**：按发布节奏选——常发版调试期建议 true（配合热重载少按一次重启），保守则 false。
5. **remote prefs 复合结构**：沿用 HC 的"字符串 `|` 分段序列化"（不引入 Parcelable/Serializable，binder 安全）。

### 风险提示

- 远程 prefs 是 **LSPosed daemon 数据库**承载，非模块私有文件——内容大小受限（`bundle.size() > 1MB` 抛 IllegalArgumentException，ConfigManager.java:499-501），大文件必须走 Provider/文件通道。
- 多入口共享进程时，各入口类都会实例化——入口类只做分发 + 按包过滤，重逻辑放被分发函数里，避免同一进程重复初始化副作用。
- release 混淆必须 keep 入口类名 + intercept 引用的宿主类方法签名（HC 用 `setId` 语义防抖，不被混淆影响）。