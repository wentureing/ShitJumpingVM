package com.nextvm.core.hook

/**
 * guest 路径重定向规则的**纯构造器**（不碰 JNI、可纯 JVM 测）。
 *
 * 白名单隔离契约：`externalDataRoot == null` 时返回的规则集与"没有内容目录外部化"时逐条相同。
 * 说明见 docs/代码镜像/core/hook/AppRedirections.md
 */
object AppRedirections {

    /** B 态外部内容根：`vsandbox/<pkg>/external_data/` 对应真机 `Android/data/<pkg>/`。 */
    const val EXTERNAL_DATA_ROOT = "/storage/emulated/0/vsandbox"

    /** 宿主包名 external 兜底前缀（via 用 getOpPackageName() 拼出来的就是它）。 */
    const val HOST_EXT_PREFIX_A = "/storage/emulated/0/Android/data/com.nextvm.app/"
    const val HOST_EXT_PREFIX_B = "/sdcard/Android/data/com.nextvm.app/"

    /**
     * @param guestPackageName guest 包名
     * @param sandboxDataDir 该实例的沙箱数据目录（`<instance>/`）
     * @param externalDataRoot 非 null 时把 `Android/data/<guest>/` 族指向它（B 态）；null = 沙箱（基线）
     */
    fun build(
        guestPackageName: String,
        sandboxDataDir: String,
        externalDataRoot: String?,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(32)
        fun add(from: String, to: String) { out.add(from to to) }

        // 内部数据目录
        add("/data/data/$guestPackageName/", "$sandboxDataDir/")
        add("/data/user/0/$guestPackageName/", "$sandboxDataDir/")

        // app 专属外部目录：真机形状 Android/data/<pkg>/，其下 files/<type>、cache/
        val extData = externalDataRoot ?: "$sandboxDataDir/sdcard/Android/data/$guestPackageName/"
        add("/storage/emulated/0/Android/data/$guestPackageName/", extData)
        add("/sdcard/Android/data/$guestPackageName/", extData)
        add("/storage/emulated/0/Android/data/$guestPackageName", extData.trimEnd('/'))
        add("/sdcard/Android/data/$guestPackageName", extData.trimEnd('/'))

        // OBB（不参与 B 态）
        val obb = "$sandboxDataDir/sdcard/Android/obb/$guestPackageName/"
        add("/storage/emulated/0/Android/obb/$guestPackageName/", obb)
        add("/sdcard/Android/obb/$guestPackageName/", obb)
        add("/storage/emulated/0/Android/obb/$guestPackageName", obb.trimEnd('/'))
        add("/sdcard/Android/obb/$guestPackageName", obb.trimEnd('/'))

        // media（不参与 B 态）
        val media = "$sandboxDataDir/sdcard/Android/media/$guestPackageName/"
        add("/storage/emulated/0/Android/media/$guestPackageName/", media)
        add("/sdcard/Android/media/$guestPackageName/", media)
        add("/storage/emulated/0/Android/media/$guestPackageName", media.trimEnd('/'))
        add("/sdcard/Android/media/$guestPackageName", media.trimEnd('/'))

        // 宿主包名兜底：guest 用 getOpPackageName()（= 宿主包名）拼出的路径落回 guest 自己的 ext 目录
        add(HOST_EXT_PREFIX_A, extData)
        add(HOST_EXT_PREFIX_B, extData)

        return out
    }
}
