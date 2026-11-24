package com.nextvm.core.virtualization.engine

import timber.log.Timber

/**
 * 进程自杀守卫（v150 从 `ActivityThreadHook.kt` 拆出）。
 *
 * ## 职责
 * 阻止 guest 应用把自己（其实是容器的 `:pN` 进程槽）"整个杀掉"：
 * - 用 `SecurityManager` 拦截 `System.exit()`；
 * - 通过 `ThreadLocal` 标志让"已被拦截"这件事可被其它 hook 查询（[isActive]）。
 *
 * ## 不负责
 * - ❌ **拦不住 `Process.killProcess()`**：它经 JNI 直接走 `kill(2)`，
 *   Java 层没有可拦截点（需要 native hook 才有完整方案）。
 * - ❌ 不做进程重启/恢复（那是容器主进程/引擎的事）。
 *
 * ## 偏差登记
 *
 * | # | 偏差 | 类型 | 说明 |
 * | --- | --- | --- | --- |
 * | 1 | 安装 `SecurityManager` | ✅ **必要偏差** | AOSP 不会阻止应用 `System.exit()`；容器必须阻止，否则一个 guest 退出会带走整个进程槽（可能还有其它 guest 的共享状态） |
 * | 2 | 只拦 `System.exit`，不拦 `killProcess` | ⚠️ **已知缺口** | 不是"故意的偏差"，是能力边界；Unity 类应用走 `killProcess` 时仍会杀进程。补法必须走 native 层 |
 * | 3 | `ThreadLocal` 而不是全局标志 | ✅ 必要 | 拦截语义是"本线程发起的退出"，避免把其它线程的正常退出也拦住 |
 *
 * ## 约束与坑
 * 1. **`System.setSecurityManager` 在新版本 Android 上可能不可用**（会抛异常/被忽略），
 *    这里只记 `Timber.d`，**不阻断启动** —— 因此本守卫是"尽力而为"，不能作为可靠保证。
 * 2. `uninstall()` 会还原 delegate，必须与 `install()` 成对调用（由 `ActivityThreadHook.uninstall()` 负责）。
 * 3. `checkPermission` 全部委托给原 manager，**不要**在这里做权限收窄，否则会误伤 guest。
 * 4. 进程级单例：重复 `install()` 只更新标志，不会重复设置 SecurityManager（幂等）。
 */
class ProcessKillGuard private constructor() {

    class KillInterceptedException : RuntimeException("Process kill blocked by NEXTVM")

    companion object {
        private const val TAG = "KillGuard"
        private val guardActive = ThreadLocal<Boolean>()

        fun install(): ProcessKillGuard {
            guardActive.set(true)

            // 安装 SecurityManager 拦截 System.exit()
            try {
                @Suppress("DEPRECATION")
                val existingManager = System.getSecurityManager()
                if (existingManager !is NextVmSecurityManager) {
                    System.setSecurityManager(NextVmSecurityManager(existingManager))
                }
            } catch (e: Exception) {
                // 部分 Android 版本不允许设置 SecurityManager
                Timber.tag(TAG).d("Could not install SecurityManager: ${e.message}")
            }

            return ProcessKillGuard()
        }

        fun isActive(): Boolean = guardActive.get() == true
    }

    fun uninstall() {
        guardActive.set(false)
        try {
            @Suppress("DEPRECATION")
            val current = System.getSecurityManager()
            if (current is NextVmSecurityManager) {
                System.setSecurityManager(current.delegate)
            }
        } catch (_: Exception) {
            // SecurityManager 清理是尽力而为
        }
    }

    @Suppress("DEPRECATION")
    private class NextVmSecurityManager(
        val delegate: SecurityManager?
    ) : SecurityManager() {
        override fun checkExit(status: Int) {
            if (isActive()) {
                Timber.tag(TAG).i("Blocked System.exit($status) from guest app")
                throw SecurityException("System.exit blocked by NEXTVM virtual environment")
            }
            delegate?.checkExit(status)
        }

        override fun checkPermission(perm: java.security.Permission?) {
            // 其它权限一律放行
            delegate?.checkPermission(perm)
        }

        override fun checkPermission(perm: java.security.Permission?, context: Any?) {
            delegate?.checkPermission(perm, context)
        }
    }
}
