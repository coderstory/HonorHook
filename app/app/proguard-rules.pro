# HonorHook ProGuard/R8 规则
# 启用混淆时（release isMinifyEnabled=true）必需——初版 release 未开混淆，以下规则备好待用。
# 照 HyperChanger proguard-rules.pro 前三条。

# libxposed 注解只存在于 compileOnly 的 api jar，运行期无类，忽略告警
-dontwarn io.github.libxposed.annotation.**

# java_init.list 里的入口类名会被 R8 重命名，此指令让 R8 改写 list 文件内容同步新类名
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 保留 XposedModule 入口（按 java_init.list 硬编码类名反射实例化，不可混淆、不可删构造器）
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}