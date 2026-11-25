package com.nextvm.core.hook

import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * FileAccessInterceptor — Monitors and redirects file access at Java level.
 *
 * This works as a companion to NativeHookBridge for cases where
 * Java I/O classes are used directly by guest apps.
 */
class FileAccessInterceptor(
    private val bridge: NativeHookBridge
) {

    companion object {
        private const val TAG = "FileIntercept"
    }

    /**
     * Intercept a File constructor call and return a redirected File.
     */
    fun interceptFile(originalPath: String): File {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("File redirect: $originalPath -> $translatedPath")
        }
        return File(translatedPath)
    }

    /**
     * Intercept a FileInputStream and return one for the redirected path.
     */
    fun interceptFileInputStream(originalPath: String): FileInputStream {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("FIS redirect: $originalPath -> $translatedPath")
        }

        // Check for fake content
        val fakeContent = bridge.getFakeContent(originalPath)
        if (fakeContent != null) {
            // Create a temp file with fake content and return stream to it
            val tempFile = File.createTempFile("nextvm_fake_", ".tmp")
            tempFile.deleteOnExit()
            tempFile.writeBytes(fakeContent)
            return FileInputStream(tempFile)
        }

        return FileInputStream(translatedPath)
    }

    /**
     * Intercept a FileOutputStream and return one for the redirected path.
     */
    fun interceptFileOutputStream(originalPath: String, append: Boolean = false): FileOutputStream {
        val translatedPath = bridge.translatePath(originalPath)
        if (translatedPath != originalPath) {
            Timber.tag(TAG).d("FOS redirect: $originalPath -> $translatedPath")
            // Ensure parent directory exists
            File(translatedPath).parentFile?.mkdirs()
        }
        return FileOutputStream(translatedPath, append)
    }

    /**
     * Intercept a File.exists() call.
     */
    fun interceptExists(path: String): Boolean {
        if (bridge.isPathHidden(path)) {
            return false
        }
        val translated = bridge.translatePath(path)
        return File(translated).exists()
    }

    /**
     * Intercept a File.canRead() call.
     */
    fun interceptCanRead(path: String): Boolean {
        if (bridge.isPathHidden(path)) {
            return false
        }
        val translated = bridge.translatePath(path)
        return File(translated).canRead()
    }

    /**
     * Intercept a File.listFiles() call.
     */
    fun interceptListFiles(path: String): Array<File>? {
        val translated = bridge.translatePath(path)
        val files = File(translated).listFiles() ?: return null

        // Filter out hidden paths
        return files.filter { file ->
            !bridge.isPathHidden(file.absolutePath)
        }.toTypedArray()
    }

    /**
     * Read /proc/self/maps with NEXTVM entries filtered out.
     */
    fun readFilteredProcMaps(): String {
        return try {
            val content = File(NativeHookBridge.PROC_SELF_MAPS).readText()
            bridge.filterProcMaps(content)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read /proc/self/maps")
            ""
        }
    }
}
