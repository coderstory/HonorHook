package top.honorhook.ui

import kotlin.random.Random

/**
 * 面板小彩蛋：每次打开 App 摇一次骰子，两个效果各自 1%，可能同时出现。
 *
 * - [textBroken]：命中时面板里所有显示文字变成 FFFF（整个面板看起来坏掉了）
 * - [cardRed]：命中时首页那张 LSPosed 状态卡变成鲜红
 *
 * 状态是普通字段而不是 Compose state：App 打开时摇一次就定死，之后 UI 不会再变，
 * 所以调用点可以直接用 [text] 包一层，不必依赖重组。
 */
object Glitch {

    /** 每个效果各自的中签概率 */
    private const val CHANCE = 0.01

    /** 命中：文字全变 FFFF */
    var textBroken: Boolean = false
        private set

    /** 命中：状态卡变鲜红 */
    var cardRed: Boolean = false
        private set

    /** 每次打开 App 调一次（MainActivity.onCreate，早于 setContent） */
    fun roll() {
        textBroken = Random.nextDouble() < CHANCE
        cardRed = Random.nextDouble() < CHANCE
    }

    /** 显示用字符串包装：命中文字彩蛋时统一变 FFFF */
    fun text(s: String): String = if (textBroken) "FFF" else s
}
