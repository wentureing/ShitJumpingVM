package com.nextvm.core.model

/** 规则注册入口：决定一个路径族的规则由哪个 setup* 入口注册。 */
enum class RedirectOwner {
    /** setupAppRedirections：data / external / obb / media */
    APP,

    /** setupPathFidelityRedirections 追加：APK / NATIVE_LIB */
    PATH_FIDELITY,
}

/**
 * 一个路径族的三种形态：真机形态 / 沙箱相对位置 / 需要翻译的宿主前缀。
 *
 * @param synthetic 真机形态（合成路径），不受 [PathPolicy] 控制
 * @param sandboxRel 相对 `<instanceDir>` 的位置；`null` = 由调用方提供绝对路径（APK）
 * @param hostForms 需要被翻译的宿主前缀（含/不含尾斜杠两种）；空 = 由 [coveredBy] 覆盖
 * @param coveredBy 覆盖本族的父族，本族不单独注册规则
 */
class PathKindSpec(
    val kind: PathKind,
    val owner: RedirectOwner,
    val synthetic: (pkg: String, type: String?) -> String,
    val sandboxRel: ((pkg: String, type: String?) -> String)? = null,
    val hostForms: (pkg: String) -> List<String> = { emptyList() },
    val coveredBy: PathKind? = null,
)

/**
 * 路径族数据表 + 纯函数（规则生成）。**不含控制流**：注册时机、ensureDir、开关判断都在消费方。
 *
 * 说明见 docs/代码镜像/core/model/PathKinds.md。
 */
object PathKinds {

    private const val EXT_A = "/storage/emulated/0/Android"
    private const val EXT_B = "/sdcard/Android"

    private fun apkPath(pkg: String): String =
        "/data/app/~~${VirtualConstants.SYNTHETIC_INSTALL_TOKEN}==/" +
            "$pkg-${VirtualConstants.SYNTHETIC_INSTALL_TOKEN}==/base.apk"

    private fun libDir(pkg: String): String =
        "/data/app/~~${VirtualConstants.SYNTHETIC_INSTALL_TOKEN}==/" +
            "$pkg-${VirtualConstants.SYNTHETIC_INSTALL_TOKEN}==/lib/arm64"

    private fun externalDataForms(pkg: String): List<String> =
        listOf("$EXT_A/data/$pkg/", "$EXT_B/data/$pkg/", "$EXT_A/data/$pkg", "$EXT_B/data/$pkg")

    private fun externalObbForms(pkg: String): List<String> =
        listOf("$EXT_A/obb/$pkg/", "$EXT_B/obb/$pkg/", "$EXT_A/obb/$pkg", "$EXT_B/obb/$pkg")

    private fun externalMediaForms(pkg: String): List<String> =
        listOf("$EXT_A/media/$pkg/", "$EXT_B/media/$pkg/", "$EXT_A/media/$pkg", "$EXT_B/media/$pkg")

    val ALL: List<PathKindSpec> = listOf(
        PathKindSpec(
            kind = PathKind.DATA_ROOT,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "/data/user/0/$pkg" },
            sandboxRel = { _, _ -> "" },
            // 真机上 /data/data 是 /data/user/0 的链接，两种写法都要翻译
            hostForms = { pkg -> listOf("/data/data/$pkg/", "/data/user/0/$pkg/") },
        ),
        PathKindSpec(
            kind = PathKind.FILES,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "/data/user/0/$pkg/files" },
            sandboxRel = { _, _ -> "files" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.CACHE,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "/data/user/0/$pkg/cache" },
            sandboxRel = { _, _ -> "cache" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.CODE_CACHE,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "/data/user/0/$pkg/code_cache" },
            sandboxRel = { _, _ -> "code_cache" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.NO_BACKUP,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "/data/user/0/$pkg/no_backup" },
            sandboxRel = { _, _ -> "no_backup" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.DATABASES,
            owner = RedirectOwner.APP,
            synthetic = { pkg, name -> "/data/user/0/$pkg/databases/$name" },
            sandboxRel = { _, name -> "databases/$name" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.APP_DIR,
            owner = RedirectOwner.APP,
            synthetic = { pkg, name -> "/data/user/0/$pkg/app_$name" },
            sandboxRel = { _, name -> "app_$name" },
            coveredBy = PathKind.DATA_ROOT,
        ),
        PathKindSpec(
            kind = PathKind.EXTERNAL_DATA,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "$EXT_A/data/$pkg" },
            sandboxRel = { pkg, _ -> "sdcard/Android/data/$pkg" },
            hostForms = { pkg -> externalDataForms(pkg) },
        ),
        PathKindSpec(
            kind = PathKind.EXTERNAL_FILES,
            owner = RedirectOwner.APP,
            synthetic = { pkg, type ->
                val base = "$EXT_A/data/$pkg/files"
                if (type != null) "$base/$type" else base
            },
            sandboxRel = { pkg, type ->
                val base = "sdcard/Android/data/$pkg/files"
                if (type != null) "$base/$type" else base
            },
            coveredBy = PathKind.EXTERNAL_DATA,
        ),
        PathKindSpec(
            kind = PathKind.EXTERNAL_CACHE,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "$EXT_A/data/$pkg/cache" },
            sandboxRel = { pkg, _ -> "sdcard/Android/data/$pkg/cache" },
            coveredBy = PathKind.EXTERNAL_DATA,
        ),
        PathKindSpec(
            kind = PathKind.OBB,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "$EXT_A/obb/$pkg" },
            sandboxRel = { pkg, _ -> "sdcard/Android/obb/$pkg" },
            hostForms = { pkg -> externalObbForms(pkg) },
        ),
        PathKindSpec(
            kind = PathKind.MEDIA,
            owner = RedirectOwner.APP,
            synthetic = { pkg, _ -> "$EXT_A/media/$pkg" },
            sandboxRel = { pkg, _ -> "sdcard/Android/media/$pkg" },
            hostForms = { pkg -> externalMediaForms(pkg) },
        ),
        PathKindSpec(
            kind = PathKind.APK,
            owner = RedirectOwner.PATH_FIDELITY,
            synthetic = { pkg, _ -> apkPath(pkg) },
            sandboxRel = null,
            hostForms = { pkg -> listOf(apkPath(pkg)) },
        ),
        PathKindSpec(
            kind = PathKind.NATIVE_LIB,
            owner = RedirectOwner.PATH_FIDELITY,
            synthetic = { pkg, _ -> libDir(pkg) },
            sandboxRel = { _, _ -> "lib" },
            hostForms = { pkg -> listOf(libDir(pkg) + "/") },
        ),
    )

    /** 共享存储根（**不含包名，不属于任何族**）：`/sdcard/` 等四种写法。 */
    val EXTERNAL_ROOT_FORMS: List<String> =
        listOf("/sdcard/", "/storage/emulated/0/", "/mnt/sdcard/", "/storage/self/primary/")

    /** 共享存储根的根形态（无尾斜杠），与 [EXTERNAL_ROOT_FORMS] 配对注册。 */
    val EXTERNAL_ROOT_BARE_FORMS: List<String> = listOf("/storage/emulated/0", "/sdcard")

    fun of(kind: PathKind): PathKindSpec = ALL.first { it.kind == kind }

    fun synthetic(kind: PathKind, pkg: String, type: String? = null): String =
        of(kind).synthetic(pkg, type)

    fun sandboxRelOf(kind: PathKind, pkg: String, type: String? = null): String? =
        of(kind).sandboxRel?.invoke(pkg, type)

    fun hostForms(kind: PathKind, pkg: String): List<String> = of(kind).hostForms(pkg)

    /**
     * 生成某入口的规则对 `(from, to)`，`to` 为绝对路径。纯函数，不注册、不建目录。
     *
     * @param apkPath APK 族的翻译目标（沙箱里的 apk 绝对路径）；为 null 时跳过该族
     */
    fun rulesFor(
        owner: RedirectOwner,
        pkg: String,
        sandboxDataDir: String,
        apkPath: String? = null,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (spec in ALL) {
            if (spec.owner != owner) continue
            val target = when {
                spec.kind == PathKind.APK -> apkPath ?: continue
                else -> {
                    val rel = spec.sandboxRel?.invoke(pkg, null) ?: continue
                    if (rel.isEmpty()) sandboxDataDir else "$sandboxDataDir/$rel"
                }
            }
            for (form in spec.hostForms(pkg)) {
                out += form to (if (form.endsWith("/")) "$target/" else target.trimEnd('/'))
            }
        }
        return out
    }

    /** 宿主包名兜底的 from（guest 用 `getOpPackageName()` 拼出宿主包名时命中）。 */
    fun hostFallbackForms(hostPkg: String): List<String> =
        listOf("$EXT_A/data/$hostPkg/", "$EXT_B/data/$hostPkg/")

    /** 宿主包名兜底的规则对：目标与 guest 自己的 EXTERNAL_DATA 相同（不得分叉）。 */
    fun hostFallbackRules(
        hostPkg: String,
        guestPkg: String,
        sandboxDataDir: String,
    ): List<Pair<String, String>> {
        val target = "$sandboxDataDir/${sandboxRelOf(PathKind.EXTERNAL_DATA, guestPkg)}/"
        return hostFallbackForms(hostPkg).map { it to target }
    }

    /** 结构自检：有合成路径却没有宿主前缀的族（除显式 `coveredBy` 外）—— 供测试与诊断用。 */
    fun kindsWithoutHostForms(): List<PathKind> =
        ALL.filter { it.hostForms("p").isEmpty() && it.coveredBy == null }.map { it.kind }
}
