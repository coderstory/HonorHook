package top.honorhook.xposed.target.appmarket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEventListener
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.callMethod
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 健康传感器锁屏门控（tamer 1.4.11 HealthSensorGate 移植，2026-11-19）。
 *
 * 挂 android.hardware.SensorManager 的 registerListener（3/4/5 参全形态）与
 * unregisterListener(2 参)：锁屏时拒掉步数/加速度计（type 1/19/18）注册（返 false + 入
 * PENDING 队列，上限 16），亮屏重放；已注册的门控型 sensor 灭屏统一 unregister。
 * SCREEN_ON/OFF 广播驱动，receiver 经 ActivityThread.currentApplication 延迟获取（tamer 同款）。
 * 只注册进 com.hihonor.health（FeatureCatalog 的 health_gate 开关挂在市场 target 下）。
 */
class TamerHealthGateHook(override val packageName: String) : TargetHook {

    private class Sess(val listener: SensorEventListener, val sensor: Sensor, val manager: Any, val args: Array<Any?>)

    private val lock = Any()
    private val sessions = ArrayList<Sess>()
    private val pending = ArrayList<Sess>()
    private val screenOn = AtomicBoolean(true)
    private val receiverReady = AtomicBoolean(false)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_HEALTH_GATE)) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_HEALTH_GATE, "TamerHealthGateHook")
            return
        }
        val pendingTasks = listOf(
            MountEngine.SimpleTask(
                id = "market-tamer:gate.reg3", className = "android.hardware.SensorManager",
                methodName = "registerListener", paramCount = 3, returnType = "boolean",
                description = "健康传感器锁屏门控（registerListener 3 参）",
            ),
            MountEngine.SimpleTask(
                id = "market-tamer:gate.reg4", className = "android.hardware.SensorManager",
                methodName = "registerListener", paramCount = 4, returnType = "boolean",
                description = "健康传感器锁屏门控（registerListener 4 参）",
            ),
            MountEngine.SimpleTask(
                id = "market-tamer:gate.reg5", className = "android.hardware.SensorManager",
                methodName = "registerListener", paramCount = 5, returnType = "boolean",
                description = "健康传感器锁屏门控（registerListener 5 参）",
            ),
            MountEngine.SimpleTask(
                id = "market-tamer:gate.unreg2", className = "android.hardware.SensorManager",
                methodName = "unregisterListener", paramCount = 2, returnType = "void",
                description = "健康传感器锁屏门控（unregisterListener 轨迹移除）",
            ),
        )
        val session = MountEngine.open(
            "TamerHealthGateHook", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pendingTasks, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                runCatching {
                    if (t.id == "market-tamer:gate.unreg2") {
                        val r = chain.proceed()
                        (chain.args.getOrNull(0) as? SensorEventListener)?.let { l ->
                            (chain.args.getOrNull(1) as? Sensor)?.let { s -> forget(l, s) }
                        }
                        r
                    } else {
                        val sensor = findSensor(chain.args)
                        val listener = chain.args.firstOrNull() as? SensorEventListener
                        if (sensor != null && isGatedType(sensor.type) && !screenOn.get() && listener != null) {
                            addPending(Sess(listener, sensor, chain.thisObject!!, chain.args.toTypedArray()))
                            session.judge(module, t, "market-tamer", "锁屏拒注册 type=${sensor.type}")
                            false
                        } else {
                            val r = chain.proceed()
                            if (r == true && sensor != null && isGatedType(sensor.type) && listener != null) {
                                track(listener, sensor, chain.thisObject!!, chain.args.toTypedArray())
                            }
                            r
                        }
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer gate 异常（已吞，放行）: ${it.message}")
                    chain.proceed()
                }
            }
        }
        ensureReceiver(module)
    }

    // —— 门控类型（tamer gatedType：accel / step_counter / step_detector）——
    private fun isGatedType(type: Int) = type == 1 || type == 19 || type == 18

    private fun findSensor(args: List<Any?>?): Sensor? =
        args?.firstOrNull { it is Sensor } as? Sensor

    private fun track(listener: SensorEventListener, sensor: Sensor, manager: Any, args: Array<Any?>) {
        synchronized(lock) {
            sessions.removeAll { it.listener === listener && it.sensor === sensor }
            sessions.add(Sess(listener, sensor, manager, args))
        }
    }

    private fun addPending(sess: Sess) {
        synchronized(lock) {
            pending.removeAll { it.listener === sess.listener && it.sensor === sess.sensor }
            if (pending.size >= 16) pending.removeAt(0) // tamer PENDING_CAP
            pending.add(sess)
        }
    }

    private fun forget(listener: SensorEventListener, sensor: Sensor) {
        synchronized(lock) {
            sessions.removeAll { it.listener === listener && it.sensor === sensor }
            pending.removeAll { it.listener === listener && it.sensor === sensor }
        }
    }

    /** SCREEN_ON/OFF 广播驱动：灭屏统一停掉门控型会话并入队，亮屏按原参数重放。 */
    private fun apply(on: Boolean, module: XposedModule) {
        if (!on) {
            val snapshot = synchronized(lock) { ArrayList(sessions).also { sessions.clear() } }
            var n = 0
            for (sess in snapshot) {
                runCatching {
                    callMethod(sess.manager, "unregisterListener", sess.listener, sess.sensor)
                    addPending(sess)
                    n++
                }
            }
            module.log(android.util.Log.INFO, MountEngine.TAG, "market-tamer gate: screen OFF, stopped $n")
        } else {
            val snapshot = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
            var n = 0
            for (sess in snapshot) {
                runCatching {
                    callMethod(sess.manager, "registerListener", *sess.args)
                    n++
                }
            }
            module.log(android.util.Log.INFO, MountEngine.TAG, "market-tamer gate: screen ON, replayed $n")
        }
    }

    /** tamer ensureReceiver：ActivityThread.currentApplication 延迟取 Context 注册亮灭屏广播。 */
    private fun ensureReceiver(module: XposedModule) {
        if (!receiverReady.compareAndSet(false, true)) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun tryRegister() {
            val ctx = runCatching {
                Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()
            if (ctx == null) {
                handler.postDelayed({ tryRegister() }, 2000)
                return
            }
            runCatching {
                ctx.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching {
                            val on = intent.action == Intent.ACTION_SCREEN_ON
                            if (intent.action == Intent.ACTION_SCREEN_OFF || on) {
                                if (screenOn.getAndSet(on) != on) apply(on, module)
                            }
                        }
                    }
                }, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
                if (!isInteractive(ctx)) {
                    screenOn.set(false)
                    apply(false, module)
                }
                module.log(android.util.Log.INFO, MountEngine.TAG, "market-tamer gate: receiver ready")
            }.onFailure {
                module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer gate receiver 失败: ${it.message}")
            }
        }
        handler.postDelayed({ tryRegister() }, 1500)
    }

    private fun isInteractive(context: Context): Boolean =
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            power.isInteractive
        }.getOrDefault(true)
}
