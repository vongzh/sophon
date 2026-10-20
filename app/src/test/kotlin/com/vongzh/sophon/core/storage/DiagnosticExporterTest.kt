package com.vongzh.sophon.core.storage

import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.vongzh.sophon.domain.contract.IStorageVault
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.domain.model.StorageUsageBreakdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream

class DiagnosticExporterTest {

    private class FakeContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.vongzh.sophon"
        override fun getPackageManager(): PackageManager? = null
    }

    private class FakeStorageVault(
        isMounted: Boolean = false,
        usbRoot: File? = null,
        override val isWriteAllowed: Boolean = false
    ) : IStorageVault {
        override val isUsbMounted: StateFlow<Boolean> = MutableStateFlow(isMounted)
        override val currentUsbRoot: StateFlow<File?> = MutableStateFlow(usbRoot)

        override fun openChunkOutputStream(
            category: RecordCategory,
            timestampMs: Long,
            cameraTag: String
        ): OutputStream? = null

        override fun queryStorageBreakdown(): StorageUsageBreakdown = StorageUsageBreakdown()

        override fun pruneOldestRecordingsIfNeeded(): Boolean = false

        override fun enforceStorageQuotas(trigger: Int): Boolean = false
    }

    @Test
    fun testExportToUsbFailsWhenUsbNotMounted() = runBlocking {
        // 安全红线二验证：在无 U 盘接入时，诊断导出器必须无条件拒绝，严禁写入机身内部闪存
        val fakeStorage = FakeStorageVault(isMounted = false, usbRoot = null)
        val exporter = DiagnosticExporter(FakeContext(), fakeStorage)
        val result = exporter.exportToUsb()

        assertFalse("Export should fail when USB is not mounted", result.success)
        assertTrue(
            "Error message should mention USB or redline 2",
            result.errorMessage?.contains("U 盘") == true || result.errorMessage?.contains("闪存") == true
        )
    }

    @Test
    fun testExportToUsbSucceedsWhenUsbMounted() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("sophon_usb_test").toFile()
        try {
            val fakeStorage = FakeStorageVault(
                isMounted = true,
                usbRoot = tempDir,
                isWriteAllowed = true
            )

            val exporter = DiagnosticExporter(FakeContext(), fakeStorage)
            val result = exporter.exportToUsb()

            assertTrue("Export should succeed when USB is mounted", result.success)
            assertTrue("Target file must exist", result.filePath != null && File(result.filePath).exists())

            val file = File(result.filePath!!)
            val content = file.readText()
            assertTrue("Diagnostic content must contain header", content.contains("SOPHON"))
            assertTrue("Diagnostic content must contain hardware section", content.contains("车机硬件与操作系统"))
            assertTrue("Diagnostic content must contain package name", content.contains("com.vongzh.sophon"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

