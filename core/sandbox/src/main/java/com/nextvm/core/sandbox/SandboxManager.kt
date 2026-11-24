package com.nextvm.core.sandbox

import android.content.Context
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SandboxManager — Manages isolated file system for virtual apps.
 *
 * Directory structure per virtual app instance:
 * virtual/
 * ├── apks/{instanceId}.apk
 * └── data/{instanceId}/
 *     ├── files/
 *     ├── cache/
 *     ├── shared_prefs/
 *     ├── databases/
 *     ├── lib/           (extracted native .so files)
 *     ├── code_cache/    (optimized DEX)
 *     ├── app_data/      (custom app directories)
 *     └── external_files/ (simulated external storage)
 */
@Singleton
class SandboxManager @Inject constructor(
    private val context: Context,
    private val virtualRoot: File
) {
    companion object {
        private const val TAG = "SandboxMgr"

        // Standard Android data subdirectories
        private val DATA_SUBDIRS = listOf(
            "files",
            "cache",
            "shared_prefs",
            "databases",
            "lib",
            "code_cache",
            "app_data",
            "external_files",
            "external_cache",
            "no_backup",
            "obb"
        )

        /**
         * Standard public directories on Android's external/shared storage.
         * File explorer apps use these via Environment.getExternalStoragePublicDirectory().
         */
        private val EXTERNAL_STORAGE_PUBLIC_DIRS = listOf(
            "Alarms",
            "Audiobooks",
            "DCIM",
            "Documents",
            "Download",
            "Movies",
            "Music",
            "Notifications",
            "Pictures",
            "Podcasts",
            "Recordings",
            "Ringtones"
        )

        /** 探针输出里允许收集的文件名（白名单，不做通配）。 */
        private val PROBE_OUTPUT_NAMES = listOf(
            "probe-report.json",
            "probe-early.json",
            "selfcheck.json"
        )

        /** 导出目录名（与沙箱根 files/virtual 平级，guest 看不到）。 */
        private const val PROBE_OUT_DIR = "probe-out"
    }

    private val dataRoot = File(virtualRoot, VirtualConstants.VIRTUAL_DATA_DIR)

    /**
     * v51: directories already ensured to exist in this process.
     *
     * Every dir getter used to call mkdirs() unconditionally, so hot
     * paths that resolve sandbox dirs per call — above all
     * VirtualContext.getApplicationInfo(), which layout measurement
     * consults constantly — paid several supervised access()/mkdir
     * syscalls per call and could saturate a guest's main thread
     * into an input-dispatch ANR (launch69: three DropBox ANRs with
     * the main thread inside File.mkdirs under getApplicationInfo).
     * Dirs are created on first use in the process and only removed
     * by deleteAppData/clearCache, which purge the cache below.
     */
    private val ensuredDirs =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun ensureDir(file: File): File {
        val path = file.absolutePath
        // v144：已登记且**确实存在**才快速返回（保留"每进程只建一次"的优化）。
        // 旧实现是 `if (ensuredDirs.add(path)) file.mkdirs()` —— 先登记再创建、
        // 且丢弃 mkdirs 的返回值，导致：一旦首次创建失败（启动早期父目录链未就绪），
        // 该路径就被永久记忆为"已确保"，之后永不重试 ->
        // guest 拿到"登记为已创建但实际不存在"的目录（Via 的 fontDirectory 崩溃就是这么来的）。
        if (ensuredDirs.contains(path) && file.isDirectory) {
            return file
        }
        // 未登记，或登记过但目录已消失 -> 真正创建；**只有创建成功才登记**。
        // `mkdirs() || isDirectory`：并发下"别人先建好了"也算成功。
        val created = file.mkdirs()
        val ok = created || file.isDirectory
        if (ok) {
            ensuredDirs.add(path)
        } else {
            ensuredDirs.remove(path)
            // v145：目录创建失败是排查的第一线索（曾使 Via 拿不到 fontDirectory 而崩溃）。
            // 失败始终写，不受 verbose 开关影响。
            com.nextvm.core.common.DebugTrace.fail(
                "ensureDir", "mkdirs FAILED path=$path"
            )
        }
        com.nextvm.core.common.DebugTrace.detail(
            "ensureDir", "path=$path created=$created ok=$ok"
        )
        return file
    }

    private fun forgetEnsuredUnder(dir: File) {
        val prefix = dir.absolutePath
        ensuredDirs.removeIf {
            it == prefix || it.startsWith(prefix + File.separator)
        }
    }

    init {
        dataRoot.mkdirs()
        ensuredDirs.add(dataRoot.absolutePath)
    }

    /**
     * Create the full data directory tree for a virtual app instance.
     * Also creates the per-app virtual external/shared storage with standard public directories.
     */
    fun createAppDataDir(instanceId: String, packageName: String) {
        val appDir = getAppDir(instanceId)

        for (subDir in DATA_SUBDIRS) {
            File(appDir, subDir).mkdirs()
        }

        // Create virtual shared/external storage with standard public directories
        val sdcard = getExternalStorageDir(instanceId)
        for (pub in EXTERNAL_STORAGE_PUBLIC_DIRS) {
            File(sdcard, pub).mkdirs()
        }
        // Android/data/{pkg} and Android/obb/{pkg} inside the virtual sdcard
        File(sdcard, "Android/data/$packageName").mkdirs()
        File(sdcard, "Android/obb/$packageName").mkdirs()
        File(sdcard, "Android/media/$packageName").mkdirs()

        Timber.tag(TAG).d("Created data dir for $instanceId at ${appDir.absolutePath}")
    }

    /**
     * v144：在 guest bind 时**一次性预建**该实例需要的全部目录。
     *
     * 为什么要预建：目录原本是"首次使用时懒创建"，而 guest 启动早期（Application.onCreate
     * 阶段）各种初始化会并发/提前取目录，容易撞上"父目录链还没建好"的时刻。
     * 配合旧 `ensureDir` 的"失败也登记"缺陷，就会产生"登记存在但实际不存在"的目录
     * —— 实测导致 Via 启动必崩（`fontDirectory == null || fontDirectory.isEmpty()`）。
     *
     * 本方法失败不阻断启动：`ensureDir` 已改为可重试。
     */
    fun ensureAppDirsReady(instanceId: String, packageName: String) {
        try {
            createAppDataDir(instanceId, packageName)
            getFilesDir(instanceId)
            getCacheDir(instanceId)
            getSharedPrefsDir(instanceId)
            getExternalStorageDir(instanceId)
            // Android/data/<pkg>/files 与 cache —— Via 取字体目录走的就是这条
            getExternalAppFilesDir(instanceId, packageName, null)
            getExternalAppCacheDir(instanceId, packageName)
            Timber.tag(TAG).d("v144 pre-created dirs for $instanceId")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "v144 pre-create dirs failed for $instanceId")
        }
    }

    /**
     * Get the root data directory for an app instance.
     */
    fun getAppDir(instanceId: String): File {
        return ensureDir(File(dataRoot, instanceId))
    }

    /**
     * Get the files directory for an app instance.
     */
    fun getFilesDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "files"))
    }

    /**
     * Get the cache directory for an app instance.
     */
    fun getCacheDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "cache"))
    }

    /**
     * Get the shared_prefs directory for an app instance.
     */
    fun getSharedPrefsDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "shared_prefs"))
    }

    /**
     * Get the databases directory for an app instance.
     */
    fun getDatabasesDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "databases"))
    }

    /**
     * Get the native lib directory for an app instance.
     */
    fun getLibDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "lib"))
    }

    /**
     * Get the path to the virtual APK for an app instance.
     * Returns null if the APK doesn't exist.
     */
    fun getApkPath(instanceId: String): String? {
        val apkFile = File(virtualRoot, "apks/${instanceId}.apk")
        return if (apkFile.exists()) apkFile.absolutePath else null
    }

    /**
     * Get the code_cache directory for an app instance.
     */
    fun getCodeCacheDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "code_cache"))
    }

    /**
     * Get the external files directory for an app instance.
     */
    fun getExternalFilesDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "external_files"))
    }

    /**
     * Delete all data for an app instance.
     */
    fun deleteAppData(instanceId: String): Boolean {
        val appDir = getAppDir(instanceId)
        val result = appDir.deleteRecursively()
        forgetEnsuredUnder(appDir)
        Timber.tag(TAG).i("Deleted data for $instanceId: $result")
        return result
    }

    /**
     * Clear cache for an app instance.
     */
    fun clearCache(instanceId: String): Boolean {
        val cacheDir = getCacheDir(instanceId)
        val result = cacheDir.deleteRecursively()
        forgetEnsuredUnder(cacheDir)
        cacheDir.mkdirs() // Recreate empty dir
        Timber.tag(TAG).d("Cleared cache for $instanceId")
        return result
    }

    /**
     * Get the total storage size used by an app instance (in bytes).
     */
    fun getAppStorageSize(instanceId: String): Long {
        return getAppDir(instanceId).walkTopDown().sumOf { it.length() }
    }

    /**
     * Get the total storage size used by all virtual apps (in bytes).
     */
    fun getTotalStorageSize(): Long {
        return virtualRoot.walkTopDown().sumOf { it.length() }
    }

    /**
     * Translate a guest app's data path to the sandboxed path.
     *
     * Guest sees: /data/data/{packageName}/files/...
     * Real path: /data/data/com.nextvm.app/virtual/data/{instanceId}/files/...
     */
    fun translatePath(
        originalPath: String,
        packageName: String,
        instanceId: String
    ): String {
        val guestDataPrefix = "/data/data/$packageName"
        val guestUserPrefix = "/data/user/0/$packageName"
        val sandboxPrefix = getAppDir(instanceId).absolutePath

        return when {
            originalPath.startsWith(guestDataPrefix) ->
                originalPath.replace(guestDataPrefix, sandboxPrefix)
            originalPath.startsWith(guestUserPrefix) ->
                originalPath.replace(guestUserPrefix, sandboxPrefix)
            else -> originalPath
        }
    }

    /**
     * Get the OBB directory for an app instance.
     * OBB files are large expansion files downloaded alongside an app.
     */
    fun getObbDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "obb"))
    }

    /**
     * Get the virtual external (shared) storage root for an app instance.
     * This replaces /sdcard or /storage/emulated/0 for the guest.
     *
     * Structure mirrors a real Android device:
     *   sdcard/
     *   ├── Alarms/
     *   ├── DCIM/
     *   ├── Documents/
     *   ├── Download/
     *   ├── Movies/
     *   ├── Music/
     *   ├── Notifications/
     *   ├── Pictures/
     *   ├── Ringtones/
     *   └── Android/
     *       ├── data/{pkg}/
     *       ├── obb/{pkg}/
     *       └── media/{pkg}/
     */
    fun getExternalStorageDir(instanceId: String): File {
        return ensureDir(File(getAppDir(instanceId), "sdcard"))
    }

    /**
     * Get a specific public directory inside the virtual external storage.
     * Equivalent to Environment.getExternalStoragePublicDirectory(type).
     *
     * @param type One of Environment.DIRECTORY_* constants (e.g., "DCIM", "Download", "Pictures")
     */
    fun getExternalStoragePublicDir(instanceId: String, type: String): File {
        return ensureDir(File(getExternalStorageDir(instanceId), type))
    }

    /**
     * Get the app-specific directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/data/{pkg}/files/{type}
     */
    fun getExternalAppFilesDir(instanceId: String, packageName: String, type: String?): File {
        val base = ensureDir(File(getExternalStorageDir(instanceId), "Android/data/$packageName/files"))
        return if (type != null) ensureDir(File(base, type)) else base
    }

    /**
     * Get the app-specific cache directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/data/{pkg}/cache
     */
    fun getExternalAppCacheDir(instanceId: String, packageName: String): File {
        return ensureDir(File(getExternalStorageDir(instanceId), "Android/data/$packageName/cache"))
    }

    /**
     * Get the app-specific media directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/media/{pkg}
     */
    fun getExternalMediaDir(instanceId: String, packageName: String): File {
        return ensureDir(File(getExternalStorageDir(instanceId), "Android/media/$packageName"))
    }

    /**
     * Get the app-specific OBB directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/obb/{pkg}
     */
    fun getExternalObbDir(instanceId: String, packageName: String): File {
        return ensureDir(File(getExternalStorageDir(instanceId), "Android/obb/$packageName"))
    }

    /**
     * Check if a path should be redirected to sandbox.
     */
    fun shouldRedirect(path: String, packageName: String): Boolean {
        return path.contains("/data/data/$packageName") ||
               path.contains("/data/user/0/$packageName")
    }

    /**
     * Check if a path is an external storage path that should be redirected.
     */
    fun isExternalStoragePath(path: String): Boolean {
        return path.startsWith("/sdcard/") ||
               path.startsWith("/sdcard") && path.length == 7 ||
               path.startsWith("/storage/emulated/0/") ||
               path.startsWith("/storage/emulated/0") && path.length == 19 ||
               path.startsWith("/mnt/sdcard/") ||
               path.startsWith("/storage/self/primary/")
    }

    /**
     * Translate an external storage path to the virtual sandbox path for a given instance.
     */
    fun translateExternalStoragePath(path: String, instanceId: String): String {
        val sdcardRoot = getExternalStorageDir(instanceId).absolutePath

        val prefixes = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0",
            "/sdcard/",
            "/sdcard",
            "/mnt/sdcard/",
            "/storage/self/primary/"
        )

        for (prefix in prefixes) {
            if (path.startsWith(prefix)) {
                val relative = path.removePrefix(prefix)
                return if (relative.isEmpty()) sdcardRoot else "$sdcardRoot/$relative"
            }
        }

        return path
    }

    // ==================== 探针输出收集（sandboxsensor P0-2）====================

    /** 已收集 / 已导出的一份探针输出。 */
    data class CollectedFile(
        val instanceId: String,
        val name: String,
        val bytes: Long,
        val sha256: String
    )

    /** 导出目录：与沙箱根 `files/virtual` 平级，不参与 guest 路径翻译，guest 看不到。 */
    val probeOutRoot: File
        get() = File(context.filesDir, PROBE_OUT_DIR)

    /**
     * 把各 guest 沙箱里的探针输出收集到容器私有目录。
     *
     * ⚠️ 用真实沙箱根 `files/virtual/data`，**不用** `virtualRoot`：
     *    后者是 `app_virtual`（本类的注入根，实测是空壳），
     *    真实 guest 数据在 `files/virtual/data`。
     *    详见 docs/records/记录-双轨沙箱路径诊断.md
     */
    fun collectProbeOutput(): List<CollectedFile> {
        val realDataRoot = File(
            context.filesDir,
            "${VirtualConstants.VIRTUAL_DIR}/${VirtualConstants.VIRTUAL_DATA_DIR}"
        )
        val outRoot = probeOutRoot.apply { mkdirs() }
        val results = mutableListOf<CollectedFile>()

        val instances = realDataRoot.listFiles() ?: return emptyList()
        for (inst in instances) {
            if (!inst.isDirectory) continue
            val filesDir = File(inst, "files")
            if (!filesDir.isDirectory) continue
            for (name in PROBE_OUTPUT_NAMES) {
                val src = File(filesDir, name)
                if (!src.isFile) continue
                val dstDir = File(outRoot, inst.name).apply { mkdirs() }
                val dst = File(dstDir, name)
                val copied = runCatching { src.copyTo(dst, overwrite = true) }
                    .getOrElse { e ->
                        Timber.tag(TAG).w(e, "collect %s/%s failed", inst.name, name)
                        null
                    } ?: continue
                val sha = sha256Of(dst)
                runCatching {
                    File(dstDir, "meta.txt").writeText(
                        "instance=${inst.name}\n" +
                            "name=$name\n" +
                            "src=${src.absolutePath}\n" +
                            "dst=${dst.absolutePath}\n" +
                            "bytes=${dst.length()}\n" +
                            "sha256=$sha\n" +
                            "collectedAt=${System.currentTimeMillis()}\n"
                    )
                }
                results += CollectedFile(inst.name, name, copied.length(), sha)
            }
        }
        writeProbeOutIndex(outRoot, results)
        Timber.tag(TAG).i("collectProbeOutput: %d file(s)", results.size)
        return results
    }

    /** 列出已收集的文件（供 CmdReceiver 的 LIST_OUT 用）。 */
    fun listProbeOutput(): List<CollectedFile> {
        val out = mutableListOf<CollectedFile>()
        val instDirs = probeOutRoot.listFiles() ?: return emptyList()
        for (instDir in instDirs) {
            if (!instDir.isDirectory) continue
            val fs = instDir.listFiles() ?: continue
            for (f in fs) {
                if (f.isFile && PROBE_OUTPUT_NAMES.contains(f.name)) {
                    out += CollectedFile(instDir.name, f.name, f.length(), sha256Of(f))
                }
            }
        }
        return out.sortedWith(compareBy({ it.instanceId }, { it.name }))
    }

    /**
     * 读取指定导出文件。instance 或 name 不合法、文件不存在则返回 null。
     *
     * 校验：
     *   * instance 只允许 `[A-Za-z0-9._-]`，长度 <= 128，且不含 `..`
     *   * name 必须在白名单里（不做通配）
     */
    fun readProbeOutput(instanceId: String, name: String): ByteArray? {
        if (!isSafeInstanceId(instanceId)) return null
        if (!PROBE_OUTPUT_NAMES.contains(name)) return null
        val f = File(File(probeOutRoot, instanceId), name)
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    private fun isSafeInstanceId(s: String): Boolean {
        if (s.isEmpty() || s.length > 128) return false
        if (s.contains("..")) return false
        for (c in s) {
            val ok = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }

    private fun sha256Of(f: File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "sha256 failed for %s", f.name)
        "?"
    }

    private fun writeProbeOutIndex(root: File, items: List<CollectedFile>) {
        val sb = StringBuilder("[\n")
        items.forEachIndexed { i, c ->
            sb.append("  {\"instance\":\"")
                .append(c.instanceId)
                .append("\",\"name\":\"")
                .append(c.name)
                .append("\",\"bytes\":")
                .append(c.bytes)
                .append(",\"sha256\":\"")
                .append(c.sha256)
                .append("\"}")
            if (i != items.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("]\n")
        runCatching { File(root, "index.json").writeText(sb.toString()) }
            .onFailure { Timber.tag(TAG).w(it, "write index.json failed") }
    }
}
