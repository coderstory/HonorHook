package top.honorhook.ui.capsule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * 灵动胶囊 C 路发送器（26 号规格书，零 hook）。
 *
 * 全链路 jadx 实证（framework.jar / framework-magic.jar，本机 MagicOS 11）：
 * 1. CapsuleData 真类 = com.hihonor.android.app.CapsuleData，在 framework-magic（boot classpath），
 *    app 进程可直接反射构造——跨进程 parcel 由 SystemUI 还原，无需复刻 Parcelable。
 * 2. Notification.setHnExtrasParcelable(key, p) 写私有 hnExtras Bundle，且 writeToParcel/
 *    Parcel 构造器成对读写 hnExtras → 跨进程携带成立。
 * 3. SystemUI 侧 NotificationEx.getHnParcelable(sbn, "smart_island_capsule_data") →
 *    CapsuleManager#onDynamicCapsuleDataAdd，此路径只过用户开关，无签名/权限校验。
 *
 * 全反射的原因：framework-magic 不参与编译（gradle 无法挂 boot 扩展 jar），
 * 且非 MagicOS 设备上必须优雅降级为 Failed 而不是 NoClassDefFoundError。
 */
object CapsulePoster {

    const val EXTRAS_KEY = "smart_island_capsule_data"
    private const val CHANNEL_ID = "capsule_demo"
    private const val NOTIFICATION_ID = 0x1A16

    /** CapsuleData.StatusBarData#TYPE_SCENE_CONTROL_SERVICE（26 号：通知路径 3:1，测试卡走 3） */
    private const val TYPE_SCENE_CONTROL = 3

    /** SystemUI 缺省 degreeLevel=23（服务端 EEM 查表非法返 -3；C 路不受服务端限制） */
    private const val DEGREE_LEVEL_DEFAULT = 23

    sealed interface Result {
        data class Ok(val notificationId: Int) : Result
        data class Failed(val step: String, val message: String) : Result
    }

    /** 发一条 image_text_timer 倒计时胶囊（30s 倒数，到 0 SystemUI 计时管理器自移除）。 */
    fun postCountdown(context: Context, mainText: String, seconds: Int): Result {
        return try {
            val capsuleClass = Class.forName("com.hihonor.android.app.CapsuleData")
            val capsule = capsuleClass.getDeclaredConstructor().newInstance()

            runSetter(capsule, "setStatusBarTemplate", "image_text_timer")
            runSetter(capsule, "setMainText", mainText as CharSequence)
            runSetter(capsule, "setMainContent", arrayOf("Re:荣耀 工具箱"))

            // ExtraData{StatusBarData{capsuleType, degreeLevel, chronometer}}
            val extraClass = Class.forName("com.hihonor.android.app.CapsuleData\$ExtraData")
            val extra = extraClass.getDeclaredConstructor().newInstance()
            val sbdClass = Class.forName("com.hihonor.android.app.CapsuleData\$StatusBarData")
            val sbd = sbdClass.getDeclaredConstructor().newInstance()
            runSetter(sbd, "setCapsuleType", TYPE_SCENE_CONTROL)
            runSetter(sbd, "setDegreeLevel", DEGREE_LEVEL_DEFAULT)
            // setChronometer(isCountDown=true, startTime)：基准 elapsedRealtime（毫秒）
            runSetter(sbd, "setChronometer", true, SystemClock.elapsedRealtime() + seconds * 1000L)
            runSetter(extra, "setStatusBarData", sbd)
            runSetter(capsule, "setExtraData", extra)

            post(context, capsule)
        } catch (e: Throwable) {
            Result.Failed("构建 CapsuleData", e.message ?: e.javaClass.simpleName)
        }
    }

    /** 发一条 image_text 文本胶囊（无计时）。 */
    fun postText(context: Context, mainText: String): Result = try {
        val capsuleClass = Class.forName("com.hihonor.android.app.CapsuleData")
        val capsule = capsuleClass.getDeclaredConstructor().newInstance()
        runSetter(capsule, "setStatusBarTemplate", "image_text")
        runSetter(capsule, "setMainText", mainText as CharSequence)
        runSetter(capsule, "setMainContent", arrayOf("Re:荣耀 工具箱"))
        val extraClass = Class.forName("com.hihonor.android.app.CapsuleData\$ExtraData")
        val extra = extraClass.getDeclaredConstructor().newInstance()
        val sbdClass = Class.forName("com.hihonor.android.app.CapsuleData\$StatusBarData")
        val sbd = sbdClass.getDeclaredConstructor().newInstance()
        runSetter(sbd, "setCapsuleType", TYPE_SCENE_CONTROL)
        runSetter(sbd, "setDegreeLevel", DEGREE_LEVEL_DEFAULT)
        runSetter(extra, "setStatusBarData", sbd)
        runSetter(capsule, "setExtraData", extra)
        post(context, capsule)
    } catch (e: Throwable) {
        Result.Failed("构建 CapsuleData", e.message ?: e.javaClass.simpleName)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, capsule: Any): Result {
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return Result.Failed("NotificationManager", "不可用")
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "灵动胶囊", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val launch = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Re:荣耀 胶囊测试")
            .setContentText("灵动胶囊 C 路投递")
            .setOngoing(true)
            .setContentIntent(launch)
            .build()

        // 核心一步：塞私有 hnExtras（反射 setHnExtrasParcelable；hidden API 拦截在此如实暴露）
        val setResult = runCatching {
            Notification::class.java
                .getMethod("setHnExtrasParcelable", String::class.java, android.os.Parcelable::class.java)
                .invoke(notification, EXTRAS_KEY, capsule as android.os.Parcelable)
        }
        setResult.getOrElse {
            return Result.Failed(
                "setHnExtrasParcelable",
                "${it.message ?: it.javaClass.simpleName}（hidden API 拦截或非 MagicOS）",
            )
        }

        return runCatching {
            nm.notify(NOTIFICATION_ID, notification)
            Result.Ok(NOTIFICATION_ID) as Result
        }.getOrElse { Result.Failed("notify", it.message ?: it.javaClass.simpleName) }
    }

    private fun runSetter(target: Any, name: String, vararg args: Any?) {
        val types = args.map { a ->
            // CharSequence 形参在真类里可能是 CharSequence/String 重载，按传参类型匹配
            when (a) {
                is CharSequence -> CharSequence::class.java
                else -> a?.javaClass ?: Any::class.java
            }
        }.toTypedArray()
        runCatching { target.javaClass.getMethod(name, *types).invoke(target, *args) }
            .recoverCatching { first ->
                // 宽松轨：按名找唯一同名单参方法（26 号宽松轨口径）
                val candidates = target.javaClass.methods.filter {
                    it.name == name && it.parameterTypes.size == args.size
                }
                require(candidates.size == 1) { throw first }
                candidates[0].invoke(target, *args)
            }
            .onFailure { throw IllegalStateException("$name 调用失败: ${it.message}", it) }
    }
}
