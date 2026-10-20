# HonorHook ProGuard/R8 规则
# release 已启用混淆 + 资源收缩（见 app/build.gradle.kts 的 release 块），下列规则为存活保证。
# 照 HyperChanger proguard-rules.pro 前三条。

# libxposed 注解只存在于 compileOnly 的 api jar，运行期无类，忽略告警
-dontwarn io.github.libxposed.annotation.**

# 入口类用具名 keep（不混淆）、R8 不改类名、java_init.list 保持全限定名，此指令无替换发生；
# 保留作兜底：若未来新增允许混淆的 XposedModule 子类，其类名仍可被同步改写进 list。
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 入口类必须具名且不混淆（禁止 allowobfuscation）——通配 + allowobfuscation 属锁步脆弱设计：
# - R8 曾把 HookEntry 改名进默认包（release 产物 java_init.list 变成 'r10'），全靠上面指令
#   同步改写，两步严格锁步：AGP/R8 升级改变改名行为时，LSPosed 会静默跳过模块且构建零报错；
# - 通配模式匹配任意 XposedModule 子类，未来新增第二个入口类会静默错配注册；
# - 具名固定后 debug/release 日志过滤口径一致（同一全限定名）。
-keep,allowoptimization public class top.honorhook.xposed.HookEntry {
    public <init>();
}

# 入口覆写方法显式锁（LSPosed 框架反射调用，R8 无静态调用方可见）：mapping.txt 实证 onPackageLoaded
# 存活依赖 R8 对覆写 compile-only 基类方法的默认处理而非规则保证，且 onPackageReady 空方法体已被
# dead-code 消除——未来填充逻辑会被静默裁掉。规则锁定后不随 R8/AGP 升级漂移。
-keepclassmembers class top.honorhook.xposed.HookEntry {
    public void onPackageLoaded(io.github.libxposed.api.XposedModuleInterface$PackageLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
}

# ---- UI 宿主入口（Application/Activity 已由 manifest android:name 引用自动 keep，此处冗余防御）----
-keep public class top.honorhook.ui.HonorHookApp { public <init>(); }
-keep public class top.honorhook.ui.MainActivity { public <init>(); }

# 功能目录 / 配置存储为跨进程共用（宿主写、hook 侧读远端 key 字面量一致），防 R8 重命名其成员/常量
-keep class top.honorhook.FeatureCatalog { *; }
-keep class top.honorhook.xposed.data.RemoteConfig { *; }
