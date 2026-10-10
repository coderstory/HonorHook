package top.honorhook.xposed.data

/** 包管理器（软件包安装程序 `com.android.packageinstaller`）云控强制表 —— key 与 hook 逻辑分离的唯一数据源。 */
object InstallerConfigTable {

    /**
     * 安全模式「默认全关」JSON —— 即 `SafeModeConfig{easyModeDefaultOpen, superProtectModeDefaultOpen}` 两个
     * `int` 字段都置 0（`SafeModeConfig.java:7-8`）。
     *
     * 消费链（`CommonConfigManager.java:137` → `vz1.java:160`）：
     * `CommonConfig h()/g()` 取出该 JSON → `vz1.j(String)` Gson 反序列化 → 写入 Settings
     * `uz1.d`/`uz1.e`（简易模式 / 超级保护模式的默认开）⇒ 全 0 = 安全模式默认不开启。
     */
    const val SAFE_MODE_ALL_OFF_JSON = """{"easyModeDefaultOpen":0,"superProtectModeDefaultOpen":0}"""

    /**
     * 「三方安装异常跳转拦截名单」不命中 —— `CommonConfigManager.j(String)` 恒 false。
     * 该方法返回「source 包是否在拦截名单内」，恒 false = 任何来源包都不被拦截（名单里常见 `"ALL"` 通配）。
     */
    const val THIRD_PARTY_JUMP_NOT_INTERCEPTED = false

    /**
     * 广告请求条件不成立 —— `lt1.b(String)`（`checkIsSupportAdRequest`）恒 false。
     * 该实现是 `wt1` 接口的**唯一**实现，`zv1.m`（V4 广告）与 `mt1.m`（V3 广告）都先问它；
     * 恒 false ⇒ 两条链路都走宿主自带的「不支持广告请求」分支（写错误码 + 回调 errorCode），
     * **不发请求、且回调完整**（不会让 presenter 挂起）。
     */
    const val AD_REQUEST_NOT_SUPPORTED = false

    /** 「是否展示安全模式引导弹窗」恒 false —— `n12.f(...)`。 */
    const val SAFE_MODE_GUIDE_DIALOG_NOT_SHOWN = false
}
