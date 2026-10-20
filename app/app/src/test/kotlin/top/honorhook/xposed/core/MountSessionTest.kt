package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 挂载会话（[MountSession]）的契约。
 *
 * 会话在**构造期**一次性确定宿主包名 / hook 名 / APK 路径 / 版本基线——
 * 原先是 `MountEngine.begin(apkPath, version)` 两阶段初始化，23 个调用点全靠人记；
 * 现在 [MountEngine] 是 object，构造调用编译不过 ⇒ 「忘记 begin」不可能再发生。
 */
class MountSessionTest {

    @Test
    fun `open returns a session carrying the hook name and host package`() {
        val session = MountEngine.open(
            hookName = "ThemeStoreHook",
            hostPackage = "com.hihonor.android.thememanager",
            apkPath = "/data/app/theme.apk",
            expectedHostVersion = "20.1.63.305",
        )
        assertEquals("ThemeStoreHook", session.hookName)
        assertEquals("com.hihonor.android.thememanager", session.hostPackage)
    }
}
