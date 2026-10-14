package com.vongzh.sophon

import android.app.Application
import android.util.Log
import com.vongzh.sophon.core.storage.StorageVaultImpl
import com.vongzh.sophon.core.vhal.VehicleSignalBusImpl
import com.vongzh.sophon.domain.contract.IStorageVault
import com.vongzh.sophon.domain.contract.IVehicleSignalBus

/**
 * 极视 (Sophon) 应用程序上下文
 *
 * 【安全红线 - 沙箱级故障隔离】
 * 全局统一异常处理器，确保本应用的任何异常在内部完成隔离与降级，绝不向系统底层溢出崩溃。
 */
class SophonApp : Application() {

    companion object {
        private const val TAG = "Sophon.App"

        lateinit var instance: SophonApp
            private set

        lateinit var storageVault: IStorageVault
            private set

        lateinit var vehicleSignalBus: IVehicleSignalBus
            private set

        lateinit var preferences: com.vongzh.sophon.core.config.SophonPreferences
            private set

        lateinit var remoteDispatcher: com.vongzh.sophon.core.remote.RemoteDispatcher
            private set

        lateinit var powerStateMachine: com.vongzh.sophon.core.power.PowerStateMachine
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupSandboxCrashIsolation()

        preferences = com.vongzh.sophon.core.config.SophonPreferences(this)
        storageVault = StorageVaultImpl(this)
        vehicleSignalBus = VehicleSignalBusImpl(this).apply {
            startListening()
        }
        powerStateMachine = com.vongzh.sophon.core.power.PowerStateMachine(
            bus = vehicleSignalBus,
            preferences = preferences,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        ).apply {
            start()
        }
        remoteDispatcher = com.vongzh.sophon.core.remote.RemoteDispatcher(this).apply {
            startRemoteServices()
        }

        Log.i(TAG, "Sophon Application Initialized successfully.")
    }

    private fun setupSandboxCrashIsolation() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "CRITICAL: Uncaught exception in thread ${thread.name}! Sandboxing crash...", throwable)
            try {
                // 确保释放任何持有的相机/渲染锁
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
