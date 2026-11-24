package com.nextvm.core.virtualization.engine.activitythread

import timber.log.Timber

/**
 * 进程自杀守卫：拦 `System.exit`（拦不住 `Process.killProcess`，需 native）。
 * 详见 `docs/代码镜像/core/virtualization/engine/ProcessKillGuard.md`。
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
