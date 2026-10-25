package top.honorhook.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.honorhook.FeatureCatalog

/**
 * [FeatureStore.applySwitchWrite] 事务判例（T4 / issue #17）：下发失败的回滚语义。
 *
 * 为什么测 applySwitchWrite 而不是 setEnabled：后者触 FrameworkState（<clinit> 起 Handler/
 * Looper，JVM 单测不可加载）与 android.util.Log（mock jar 抛 not mocked）；事务本体已抽缝，
 * remoteWrite / states / prefs 全注入，纯 JVM 可驱动。states 用普通 HashMap 即可——事务
 * 只按 MutableMap 语义读写，SnapshotStateMap 的可观察性属 UI 呈现层，验收走真机清单。
 *
 * 各用例用互不相同的 feature key：FeatureStore 是 object 单例，featureIndex（lazy 目录索引）
 * 跨用例共享且只读，但避免任何未来的可变单例态串场。
 */
class FeatureStoreRollbackTest {

    /** 记录型 prefs fake（SharedPreferences 与 Editor 二合一）：只实现事务触到的方法，未触到的抛错防静默空转。 */
    private class RecordingPrefs : SharedPreferences, SharedPreferences.Editor {
        val booleans = mutableListOf<Pair<String, Boolean>>()
        override fun edit(): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            booleans += key!! to value
            return this
        }
        override fun apply() {}
        override fun getAll(): MutableMap<String, *> = error("unused")
        override fun getString(p0: String?, p1: String?): String? = error("unused")
        override fun getStringSet(p0: String?, p1: MutableSet<String>?): MutableSet<String>? = error("unused")
        override fun getInt(p0: String?, p1: Int): Int = error("unused")
        override fun getLong(p0: String?, p1: Long): Long = error("unused")
        override fun getFloat(p0: String?, p1: Float): Float = error("unused")
        override fun getBoolean(p0: String?, p1: Boolean): Boolean = error("unused")
        override fun contains(p0: String?): Boolean = error("unused")
        override fun registerOnSharedPreferenceChangeListener(
            p0: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = error("unused")
        override fun unregisterOnSharedPreferenceChangeListener(
            p0: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = error("unused")
        override fun putString(p0: String?, p1: String?): SharedPreferences.Editor = error("unused")
        override fun putStringSet(p0: String?, p1: MutableSet<String>?): SharedPreferences.Editor = error("unused")
        override fun putInt(p0: String?, p1: Int): SharedPreferences.Editor = error("unused")
        override fun putLong(p0: String?, p1: Long): SharedPreferences.Editor = error("unused")
        override fun putFloat(p0: String?, p1: Float): SharedPreferences.Editor = error("unused")
        override fun remove(p0: String?): SharedPreferences.Editor = error("unused")
        override fun clear(): SharedPreferences.Editor = error("unused")
        override fun commit(): Boolean = error("unused")
    }

    @Test
    fun `write failure rolls back mirror and local prefs to old value`() {
        val states = HashMap<String, Boolean>()
        val prefs = RecordingPrefs()
        var seenError: Throwable? = null
        val result = FeatureStore.applySwitchWrite(
            FeatureCatalog.KEY_MMS_AD_OFF, false,
            remoteWrite = { throw RuntimeException("binder died") },
            states = states, prefs = prefs,
            onWriteError = { seenError = it },
        )
        assertEquals(FeatureStore.SetEnabledResult.WRITE_FAILED, result)
        assertTrue("onWriteError 应把远端异常带回给 setEnabled 的日志层", seenError is RuntimeException)
        // states 与 prefs 都回到旧值（该 key 目录默认开）——不允许「视觉回滚了、prefs 还是新值」的漂移
        assertEquals(true, states[FeatureCatalog.KEY_MMS_AD_OFF])
        assertEquals(
            "本地 prefs 写序必须是 先写新值、失败后回滚旧值",
            listOf(FeatureCatalog.KEY_MMS_AD_OFF to false, FeatureCatalog.KEY_MMS_AD_OFF to true),
            prefs.booleans,
        )
    }

    @Test
    fun `rollback target is catalog default when mirror has no entry`() {
        val states = HashMap<String, Boolean>()
        val result = FeatureStore.applySwitchWrite(
            FeatureCatalog.KEY_SYSTEMUI_HIDE_5G, true,
            remoteWrite = { throw RuntimeException("binder died") },
            states = states, prefs = RecordingPrefs(),
        )
        assertEquals(FeatureStore.SetEnabledResult.WRITE_FAILED, result)
        // 镜像缺项（init 未跑/新装）时旧值 = 目录默认（该 key 默认关），回滚不得落在「新值」上
        assertEquals(false, states[FeatureCatalog.KEY_SYSTEMUI_HIDE_5G])
    }

    @Test
    fun `write failure rolls back excluded partner in same transaction`() {
        val states = HashMap<String, Boolean>()
        // 互斥对（清后台 直杀/管家广播）目录默认均关——先铺「对方开」的现实前置
        states[FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER] = true
        val prefs = RecordingPrefs()
        var sent: Map<String, Boolean>? = null
        val result = FeatureStore.applySwitchWrite(
            FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS, true,
            remoteWrite = {
                sent = it
                throw RuntimeException("binder died")
            },
            states = states, prefs = prefs,
        )
        assertEquals(FeatureStore.SetEnabledResult.WRITE_FAILED, result)
        // 主写入与互斥联动必须同一批 mutation 下发（单 editor 原子性）
        assertEquals(
            mapOf(
                FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS to true,
                FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER to false,
            ),
            sent,
        )
        // 对方被联动关过后必须一并弹回开——用户没碰它，视觉停在「被关」即为谎报
        assertEquals(true, states[FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER])
        assertTrue(
            "本地 prefs 的互斥对方也要回滚",
            FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER to true in prefs.booleans,
        )
    }

    @Test
    fun `successful push keeps new values everywhere`() {
        val states = HashMap<String, Boolean>()
        states[FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER] = true
        var sent: Map<String, Boolean>? = null
        val result = FeatureStore.applySwitchWrite(
            FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS, true,
            remoteWrite = { sent = it },
            states = states, prefs = RecordingPrefs(),
        )
        assertEquals(FeatureStore.SetEnabledResult.OK, result)
        // 成功路径无回滚：新值落定 + 互斥联动生效（对方关）
        assertEquals(true, states[FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS])
        assertEquals(false, states[FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER])
        assertEquals(
            mapOf(
                FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_ATMS to true,
                FeatureCatalog.KEY_LAUNCHER_RECENTS_KEEP_MANAGER to false,
            ),
            sent,
        )
    }

    @Test
    fun `not connected keeps local writes (existing behavior pinned)`() {
        val states = HashMap<String, Boolean>()
        val prefs = RecordingPrefs()
        val result = FeatureStore.applySwitchWrite(
            FeatureCatalog.KEY_OTA_BLOCK, false,
            remoteWrite = null,
            states = states, prefs = prefs,
        )
        assertEquals(FeatureStore.SetEnabledResult.NOT_CONNECTED, result)
        // 框架未激活既有行为（T4 明确不动）：本地写入保留、不回滚——「改不动」的叙述归常驻 banner
        assertEquals(false, states[FeatureCatalog.KEY_OTA_BLOCK])
        assertEquals(listOf(FeatureCatalog.KEY_OTA_BLOCK to false), prefs.booleans)
    }
}
