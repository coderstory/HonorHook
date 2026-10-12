package top.honorhook.xposed.data

/**
 * 挂载点的**数据形态**（抽象层）。
 *
 * 表里只放「挂哪儿、挂成什么」；「怎么改」由目标侧按 kind 分派的 intercept 负责。
 * 版本分发用 [top.honorhook.xposed.core.HostVersion.pick]：精确命中 → 默认套 → 无表跳过。
 *
 * kind 用字符串而非目标私有枚举，避免抽象层反向依赖某个目标的实现。
 */
data class HookPoint(
    val id: String,
    val kind: String,
    val className: String,
    val methodName: String,
    val paramCount: Int,
    val returnType: String,
    val value: Any? = null,
    val description: String = "",
    val dexStrings: List<String>? = null,
    val dexParamTypes: List<String>? = null,
    val dexClassStrings: List<String>? = null,
    val dexClassFieldTypes: List<String>? = null,
    val forceArgIndex: Int = 0,
    /** 所属功能开关 key（FeatureCatalog）；空 = 常挂，不受开关控制。 */
    val feature: String = "",
    /** 功能点 id（通路汇总用，与 MountEngine.Task.group 契约对齐，如 "A1"）；空 = 探针/载体类，不参与汇总。 */
    val group: String = "",
    val uriEmptyRoute: Boolean = false,
    /** 下载链取值路径：THIS / GET_DOWNLOAD_INFO（目标侧映射成自己的枚举）。 */
    val uriOwner: String = "THIS",
    /** 惰性值声明式表达：`类全名.枚举字段名`（依赖类加载，运行期反射取）。 */
    val enumRef: String? = null,
)
