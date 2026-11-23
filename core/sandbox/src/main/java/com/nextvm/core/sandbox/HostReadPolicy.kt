package com.nextvm.core.sandbox

import java.io.File

/**
 * 宿主代读的路径白名单（sandboxsensor P1-2）。
 *
 * 用途：WebView 的渲染跑在 isolated 进程里，读不到容器沙箱；宿主有权限，
 * 可以代它打开文件、通过 stream 交给 WebView。但**宿主代读意味着替 guest
 * 打开了它本不该打开的文件**，所以必须有严格的白名单，否则等于给 guest 开后门。
 *
 * 允许范围（默认拒绝）：
 *   1) guest 自己的沙箱      <sandboxDataRoot>/<instanceId>/ 下的任意路径
 *   2) 容器自己的外部目录    <externalRoot>/Android/data/com.nextvm.app/ 下的任意路径
 *   3) 导入目录              <externalRoot>/Download/ 下的任意路径（与 p18 导入豁免同一语义）
 *
 * 拒绝：其他 guest 的沙箱、其他应用、/system、/vendor、/apex、其余公共目录。
 *
 * 四条硬约束（缺一条就是安全洞）：
 *   ① canonicalPath 规范化 —— 防 `../` 逃逸、解析软链
 *   ② 前缀后必须加 File.separator —— 防 `/x/data/abc` 命中 `/x/data/abcd`
 *   ③ 按 instanceId 隔离 —— A guest 不能读 B guest 的沙箱
 *   ④ 软链由 ① 解析后自然被 ②③ 的绝对前缀拦住
 */
object HostReadPolicy {

    /** 默认外部存储根。测试可注入其他值。 */
    const val DEFAULT_EXTERNAL_ROOT = "/storage/emulated/0"

    /** 超过这个长度一律拒绝（防 DoS 与异常路径）。 */
    private const val MAX_PATH_LENGTH = 4096

    /** 容器被授权读取的外部区域（相对 externalRoot）。 */
    private val HOST_ALLOWED_RELATIVE = listOf(
        "Android/data/com.nextvm.app",
        "Download",
    )

    /**
     * 判断 [requested] 是否允许宿主代读。
     *
     * @param sandboxDataRoot 真实沙箱数据根（`files/virtual/data`）
     * @param instanceId      发起请求的 guest 实例
     * @param requested       guest 请求的路径（可能是虚拟路径 / 相对路径 / 软链）
     * @param externalRoot    外部存储根，默认 [DEFAULT_EXTERNAL_ROOT]
     * @return 规范化后的真实路径；不允许则 `null`
     */
    fun resolve(
        sandboxDataRoot: File,
        instanceId: String,
        requested: String,
        externalRoot: String = DEFAULT_EXTERNAL_ROOT,
    ): String? {
        // 0) 输入基本校验
        if (requested.isBlank()) return null
        if (requested.length > MAX_PATH_LENGTH) return null
        if (requested.indexOf('\u0000') >= 0) return null

        // ① canonicalPath 规范化：解析 `..` 与软链
        val real = runCatching { File(requested).canonicalPath }.getOrNull() ?: return null

        // ② 自己沙箱：前缀后必须加分隔符，防前缀攻击
        if (isSafeInstanceId(instanceId)) {
            val own = runCatching {
                File(sandboxDataRoot, instanceId).canonicalPath + File.separator
            }.getOrNull()
            if (own != null && real.startsWith(own)) return real
        }

        // ③ 容器外部区域
        for (rel in HOST_ALLOWED_RELATIVE) {
            val allowed = File(externalRoot, rel)
            val prefix = runCatching { allowed.canonicalPath + File.separator }.getOrNull()
                ?: continue
            if (real.startsWith(prefix)) return real
        }

        // ④ 其他一律拒绝
        return null
    }

    /**
     * instanceId 是否安全（可拼进路径）。
     *
     * 只允许 `[A-Za-z0-9._-]`，长度 <= 128，且不含 `..`。
     */
    fun isSafeInstanceId(id: String): Boolean {
        if (id.isEmpty() || id.length > 128) return false
        if (id.contains("..")) return false
        for (c in id) {
            val ok = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }
}
