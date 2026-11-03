package top.honorhook.tools.ghostlock.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * 手写 Binder Stub/Proxy，等价替代上游 AIDL 三件套
 * （ghostlock app/src/main/aidl/com/ghostlock/app/shizuku/ 三份 aidl 文件）。
 * 为什么不用 .aidl：AGP 的 aidl 编译器二进制只有 x86_64 linux 版（本机
 * tools/android-sdk/build-tools/36.0.0/aidl 实测 EM_X86_64），Android 官方无
 * linux-arm64 发布；本机 /storage/emulated 全区 noexec——编译期无法在本机跑 aidl
 * （2026-11-03 实证）。事务码与上游 .aidl 逐一对齐：
 * beginCve98260=5 / appendCve98260=6 / runCve98260=7 / destroy=16777114；
 * callback 方法沿用编译器默认序（onLog=1 / onComplete=2 / onStatus=1）。
 * 两侧（本 app 进程 + Shizuku 拉起的 shell-uid UserService 进程）加载同一份类，
 * 与 AIDL 生成的 Stub/Proxy 语义一致；不追求与 AIDL 字节级兼容（对称编解码即可）。
 */

/** 上游 IGhostlockCallback.aidl */
interface IGhostlockCallback : android.os.IInterface {
    fun onLog(line: String)
    fun onComplete(exitCode: Int)

    abstract class Stub : Binder(), IGhostlockCallback {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_onLog -> {
                    data.enforceInterface(DESCRIPTOR)
                    onLog(data.readString() ?: "")
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_onComplete -> {
                    data.enforceInterface(DESCRIPTOR)
                    onComplete(data.readInt())
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IGhostlockCallback {
            override fun asBinder(): IBinder = remote
            override fun onLog(line: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(line)
                    remote.transact(TRANSACTION_onLog, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            override fun onComplete(exitCode: Int) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(exitCode)
                    // oneway：UserService 里 onComplete 之后服务不再回复，等 readException 会挂死
                    remote.transact(TRANSACTION_onComplete, data, null, Binder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            }

            companion object {
                private const val DESCRIPTOR = "top.honorhook.tools.ghostlock.shizuku.IGhostlockCallback"
                private const val TRANSACTION_onLog = Binder.FIRST_CALL_TRANSACTION + 0
                private const val TRANSACTION_onComplete = Binder.FIRST_CALL_TRANSACTION + 1
            }
        }

        companion object {
            private const val DESCRIPTOR = "top.honorhook.tools.ghostlock.shizuku.IGhostlockCallback"
            private const val TRANSACTION_onLog = Binder.FIRST_CALL_TRANSACTION + 0
            private const val TRANSACTION_onComplete = Binder.FIRST_CALL_TRANSACTION + 1
            fun asInterface(obj: IBinder?): IGhostlockCallback? =
                obj?.let { (it.queryLocalInterface(DESCRIPTOR) as? IGhostlockCallback) ?: Proxy(it) }
        }
    }
}

/** 上游 IGhostlockStatusCallback.aidl */
interface IGhostlockStatusCallback : android.os.IInterface {
    fun onStatus(step: String, status: String)

    abstract class Stub : Binder(), IGhostlockStatusCallback {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACTION_onStatus) {
                data.enforceInterface(DESCRIPTOR)
                onStatus(data.readString() ?: "", data.readString() ?: "")
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IGhostlockStatusCallback {
            override fun asBinder(): IBinder = remote
            override fun onStatus(step: String, status: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(step)
                    data.writeString(status)
                    remote.transact(TRANSACTION_onStatus, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }

        companion object {
            private const val DESCRIPTOR = "top.honorhook.tools.ghostlock.shizuku.IGhostlockStatusCallback"
            private const val TRANSACTION_onStatus = Binder.FIRST_CALL_TRANSACTION + 0
            fun asInterface(obj: IBinder?): IGhostlockStatusCallback? =
                obj?.let { (it.queryLocalInterface(DESCRIPTOR) as? IGhostlockStatusCallback) ?: Proxy(it) }
        }
    }
}

/** 上游 IGhostlockUserService.aidl（只保留 CVE-2026-98260 默认路径三个方法 + destroy） */
interface IGhostlockUserService : android.os.IInterface {
    fun beginCve98260()
    fun appendCve98260(chunk: ByteArray)
    fun runCve98260(su: ByteArray, managerPackage: String?, callback: IGhostlockCallback, statusCallback: IGhostlockStatusCallback)
    fun destroy()

    abstract class Stub : Binder(), IGhostlockUserService {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_begin -> {
                    data.enforceInterface(DESCRIPTOR)
                    beginCve98260()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_append -> {
                    data.enforceInterface(DESCRIPTOR)
                    appendCve98260(data.createByteArray() ?: ByteArray(0))
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_run -> {
                    data.enforceInterface(DESCRIPTOR)
                    val su = data.createByteArray() ?: ByteArray(0)
                    val managerPackage = if (data.readInt() != 0) data.readString() else null
                    // asInterface(binder) 只在 binder 非空时有 null 可能，这里 readStrongBinder 不为空
                    val callback = IGhostlockCallback.Stub.asInterface(data.readStrongBinder())!!
                    val statusCallback = IGhostlockStatusCallback.Stub.asInterface(data.readStrongBinder())!!
                    runCve98260(su, managerPackage, callback, statusCallback)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_destroy -> {
                    data.enforceInterface(DESCRIPTOR)
                    destroy()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IGhostlockUserService {
            override fun asBinder(): IBinder = remote
            override fun beginCve98260() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_begin, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            override fun appendCve98260(chunk: ByteArray) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeByteArray(chunk)
                    remote.transact(TRANSACTION_append, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            override fun runCve98260(su: ByteArray, managerPackage: String?, callback: IGhostlockCallback, statusCallback: IGhostlockStatusCallback) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeByteArray(su)
                    if (managerPackage != null) {
                        data.writeInt(1)
                        data.writeString(managerPackage)
                    } else {
                        data.writeInt(0)
                    }
                    data.writeStrongBinder(callback.asBinder())
                    data.writeStrongBinder(statusCallback.asBinder())
                    // run 内部立即返回、异步执行，onComplete 经 callback 回来
                    remote.transact(TRANSACTION_run, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            override fun destroy() {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_destroy, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }

        companion object {
            private const val DESCRIPTOR = "top.honorhook.tools.ghostlock.shizuku.IGhostlockUserService"
            /** 与 AIDL 生成的 Stub.asInterface 同语义：本地 binder 直返实现，远端包 Proxy。 */
            fun asInterface(obj: IBinder?): IGhostlockUserService? =
                obj?.let { (it.queryLocalInterface(DESCRIPTOR) as? IGhostlockUserService) ?: Proxy(it) }
            // 事务码照上游 IGhostlockUserService.aidl 的显式编号，顺序不可变
            private const val TRANSACTION_begin = 5
            private const val TRANSACTION_append = 6
            private const val TRANSACTION_run = 7
            private const val TRANSACTION_destroy = 16777114
        }
    }
}
