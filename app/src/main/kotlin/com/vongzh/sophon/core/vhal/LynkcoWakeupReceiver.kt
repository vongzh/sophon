package com.vongzh.sophon.core.vhal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.remote.RemoteDispatcher
import com.vongzh.sophon.service.SophonCoreService

/**
 * 领克 08 原厂休眠唤醒事件协同广播接收器 (LynkcoWakeupReceiver)
 *
 * 【原厂顺风车休眠唤醒协同】
 * 监听 FlymeAuto / 领克底层整车状态广播：
 * - com.lynkco.intent.action.STR_RESUME (整车从深度休眠恢复)
 * - com.lynkco.intent.action.SYSTEM_READY (系统准备就绪)
 * - com.lynkco.intent.action.WAKEUP (原厂远程唤醒)
 *
 * 【底线一合规保障】
 * 车辆深休眠期间不私自持锁；随原厂顺风车苏醒时即刻就绪，并在熄火时一同释放资源休眠。
 */
class LynkcoWakeupReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sophon.LynkcoWakeup"

        const val ACTION_STR_RESUME = "com.lynkco.intent.action.STR_RESUME"
        const val ACTION_SYSTEM_READY = "com.lynkco.intent.action.SYSTEM_READY"
        const val ACTION_WAKEUP = "com.lynkco.intent.action.WAKEUP"
        const val ACTION_STR_SUSPEND = "com.lynkco.intent.action.STR_SUSPEND"
        const val ACTION_PRE_SLEEP = "com.lynkco.intent.action.PRE_SLEEP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "LynkCo vehicle power broadcast received: $action")

        when (action) {
            ACTION_STR_RESUME, ACTION_SYSTEM_READY, ACTION_WAKEUP -> {
                Log.i(TAG, "Vehicle awakened by OEM power state. Starting core services...")
                try {
                    SophonApp.powerStateMachine.notifyStrResume()
                } catch (e: Exception) {
                    Log.w(TAG, "PowerStateMachine not ready yet", e)
                }

                val serviceIntent = Intent(context, SophonCoreService::class.java)
                context.startService(serviceIntent)

                try {
                    SophonApp.remoteDispatcher.sendMultiChannelAlert("领克08 车辆唤醒", "车辆电源已就绪，极视记录仪处于工作态。")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send wakeup alert", e)
                }
            }

            ACTION_STR_SUSPEND, ACTION_PRE_SLEEP, Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                Log.w(TAG, "Vehicle entering STR SUSPEND or SHUTDOWN! Emergency finalizing MP4 chunk...")
                try {
                    SophonApp.powerStateMachine.notifyStrSuspend()
                } catch (e: Exception) {
                    Log.w(TAG, "PowerStateMachine not ready yet", e)
                }

                val finalizeIntent = Intent(context, SophonCoreService::class.java).apply {
                    this.action = SophonCoreService.ACTION_EMERGENCY_FINALIZE
                }
                context.startService(finalizeIntent)
            }
        }
    }
}
