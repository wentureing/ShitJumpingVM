package com.nextvm.core.virtualization.diag

import java.io.File

/**
 * 「guest 侧自检」的请求文件协议：主进程写、guest 进程首次 resume 消费。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/diag/SelfCheckRequest.md`
 */
internal object SelfCheckRequest {

    const val FILE_NAME = "selfcheck.request"

    fun fileOf(guestFilesDir: File): File = File(guestFilesDir, FILE_NAME)

    fun isPending(guestFilesDir: File): Boolean = fileOf(guestFilesDir).exists()

    /** 写请求（带时间戳，便于事后判断"是哪次触发"）。返回是否写入成功。 */
    fun request(guestFilesDir: File): Boolean = try {
        guestFilesDir.mkdirs()
        fileOf(guestFilesDir).writeText(System.currentTimeMillis().toString())
        true
    } catch (_: Throwable) {
        false
    }

    /** 消费请求：存在则删除并返回 true（保证一个请求只跑一次）。 */
    fun consume(guestFilesDir: File): Boolean {
        val f = fileOf(guestFilesDir)
        return try {
            if (!f.exists()) return false
            f.delete()
        } catch (_: Throwable) {
            false
        }
    }
}
