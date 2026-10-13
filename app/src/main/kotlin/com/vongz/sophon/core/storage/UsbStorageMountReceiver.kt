package com.vongz.sophon.core.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vongz.sophon.SophonApp

/**
 * 外置 U 盘热插拔与挂载状态动态感知广播接收器 (UsbStorageMountReceiver)
 *
 * 【鲁棒性保证 - 即插即用即录】
 * 1. 动态感知 U 盘插入、拔出、挂载与卸载广播；
 * 2. 插入时秒级刷新外置卷目录结构并自愈录制管线；
 * 3. 拔出时安全切断写流，内存 Ring Buffer 优雅挂起，杜绝 I/O 阻塞或空指针崩溃。
 */
class UsbStorageMountReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Sophon.UsbReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Storage broadcast received: $action (Data: ${intent.dataString})")

        when (action) {
            Intent.ACTION_MEDIA_MOUNTED,
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
            "android.os.storage.action.VOLUME_STATE_CHANGED" -> {
                Log.i(TAG, "External storage attached/mounted. Refreshing StorageVault volumes...")
                (SophonApp.storageVault as? StorageVaultImpl)?.refreshStorageVolumes()
            }

            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            "android.hardware.usb.action.USB_DEVICE_DETACHED" -> {
                Log.w(TAG, "External storage unmounted/ejected! Refreshing StorageVault volumes...")
                (SophonApp.storageVault as? StorageVaultImpl)?.refreshStorageVolumes()
            }
        }
    }
}
