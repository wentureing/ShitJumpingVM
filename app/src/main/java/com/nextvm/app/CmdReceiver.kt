package com.nextvm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.nextvm.core.sandbox.SandboxManager
import com.nextvm.core.virtualization.engine.VirtualEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 命令行入口（sandboxsensor 补丁，v2）。
 *
 * 目的：让容器可以被 adb / 脚本驱动，不需要点 UI。
 *
 * 命令一览：
 *
 *   # 安装（path 必须是容器可读的路径；/sdcard 会被容器自身重定向，
 *   #       建议先 push 到 /data/local/tmp 再复制进 app 私有目录）
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.INSTALL --es path /data/user/0/com.nextvm.app/files/inbox/x.apk
 *
 *   # 启动 LAUNCHER Activity
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.LAUNCH --es pkg com.example.app
 *
 *   # 启动指定 Activity（新增：解决「容器只认 LAUNCHER」的限制）
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.LAUNCH_ACTIVITY \
 *       --es pkg com.example.app --es activity com.example.app.SomeActivity
 *
 *   # 卸载
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.UNINSTALL --es pkg com.example.app
 *
 *   # 路径欺骗豁免：这些包不做任何路径形态欺骗（不产生合成路径），但仍隔离到沙箱
 *   # 改完需重启容器/guest 生效
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.SPOOF_EXEMPT_ADD --es pkg mark.via
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver \
 *       -a com.nextvm.cmd.SPOOF_EXEMPT_REMOVE --es pkg mark.via
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver -a com.nextvm.cmd.SPOOF_EXEMPT_LIST
 *
 *   # 列出已安装的 guest（结果打到 logcat -s CmdReceiver）
 *   adb shell am broadcast -n com.nextvm.app/.CmdReceiver -a com.nextvm.cmd.LIST
 *
 * 结果统一写 logcat，tag=CmdReceiver：
 *   adb logcat -d -s CmdReceiver
 *
 * 注意：exported receiver 无权限保护，仅用于实验环境；正式化需加自定义 permission
 * 或校验 callingUid。
 */
class CmdReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CmdEntryPoint {
        fun engine(): VirtualEngine
        fun sandbox(): SandboxManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val engine = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                CmdEntryPoint::class.java
            ).engine()
        } catch (t: Throwable) {
            Log.e(TAG, "engine unavailable: ${t.message}", t)
            return
        }
        // 探针输出导出用的沙箱管理器（与 engine 分开取，任一方缺失不影响另一方）
        val sandbox = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                CmdEntryPoint::class.java
            ).sandbox()
        } catch (t: Throwable) {
            Log.e(TAG, "sandbox unavailable: ${t.message}", t)
            null
        }

        when (action) {
            ACTION_INSTALL -> {
                val path = intent.getStringExtra("path")
                if (path.isNullOrBlank()) {
                    Log.w(TAG, "INSTALL: missing --es path")
                    return
                }
                val r = engine.installApp(path)
                Log.i(TAG, "INSTALL $path -> $r")
            }

            ACTION_LAUNCH -> {
                val pkg = intent.getStringExtra("pkg")
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "LAUNCH: missing --es pkg")
                    return
                }
                val r = engine.launchAppByPackage(pkg)
                Log.i(TAG, "LAUNCH $pkg -> $r")
            }

            ACTION_LAUNCH_ACTIVITY -> {
                val pkg = intent.getStringExtra("pkg")
                val activity = intent.getStringExtra("activity")
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "LAUNCH_ACTIVITY: missing --es pkg")
                    return
                }
                if (activity.isNullOrBlank()) {
                    Log.w(TAG, "LAUNCH_ACTIVITY: missing --es activity（可先用 LAUNCH 启动 LAUNCHER）")
                    return
                }
                val r = engine.launchAppByPackage(pkg, activity)
                Log.i(TAG, "LAUNCH_ACTIVITY $pkg/$activity -> $r")
            }

            ACTION_UNINSTALL -> {
                val pkg = intent.getStringExtra("pkg")
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "UNINSTALL: missing --es pkg")
                    return
                }
                val r = engine.uninstallAppByPackage(pkg)
                Log.i(TAG, "UNINSTALL $pkg -> $r")
            }

            ACTION_LIST -> {
                try {
                    val apps = engine.getInstalledApps()
                    Log.i(TAG, "LIST count=${apps.size}")
                    apps.forEach {
                        Log.i(
                            TAG,
                            "APP pkg=${it.packageName} instance=${it.instanceId} " +
                                    "slot=${it.processSlot} main=${it.mainActivity} apk=${it.apkPath}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "LIST failed: ${t.message}", t)
                }
            }

            ACTION_COLLECT_OUT -> {
                if (sandbox == null) {
                    Log.e(TAG, "COLLECT_OUT: sandbox unavailable")
                    return
                }
                try {
                    val collected = sandbox.collectProbeOutput()
                    collected.forEach {
                        Log.i(
                            TAG,
                            "COLLECT instance=${it.instanceId} name=${it.name} " +
                                "bytes=${it.bytes} sha=${it.sha256.take(8)}"
                        )
                    }
                    Log.i(TAG, "COLLECT_OUT done files=${collected.size}")
                } catch (t: Throwable) {
                    Log.e(TAG, "COLLECT_OUT failed: ${t.message}", t)
                }
            }

            ACTION_LIST_OUT -> {
                if (sandbox == null) {
                    Log.e(TAG, "LIST_OUT: sandbox unavailable")
                    return
                }
                try {
                    val list = sandbox.listProbeOutput()
                    list.forEach {
                        Log.i(
                            TAG,
                            "OUT ${it.instanceId} ${it.name} ${it.bytes} sha=${it.sha256.take(8)}"
                        )
                    }
                    Log.i(TAG, "LIST_OUT done files=${list.size}")
                } catch (t: Throwable) {
                    Log.e(TAG, "LIST_OUT failed: ${t.message}", t)
                }
            }

            ACTION_CAT_OUT -> {
                if (sandbox == null) {
                    Log.e(TAG, "CAT_OUT: sandbox unavailable")
                    return
                }
                val instance = intent.getStringExtra("instance")
                val name = intent.getStringExtra("name")
                if (instance.isNullOrBlank() || name.isNullOrBlank()) {
                    Log.w(TAG, "CAT_OUT: missing --es instance / --es name")
                    return
                }
                val bytes = sandbox.readProbeOutput(instance, name)
                if (bytes == null) {
                    Log.e(
                        TAG,
                        "CAT_OUT failed: invalid or missing instance=$instance name=$name " +
                            "(instance 只允许字母数字._- 且不含 ..；name 必须白名单)"
                    )
                    return
                }
                emitOutChunks(instance, name, bytes)
            }

            ACTION_SPOOF_EXEMPT_ADD -> {
                val pkg = intent.getStringExtra("pkg")
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "SPOOF_EXEMPT_ADD: missing --es pkg")
                    return
                }
                val changed = com.nextvm.core.model.PathSpoofConfig.setGuestExempt(pkg, true)
                Log.i(
                    TAG,
                    "SPOOF_EXEMPT_ADD $pkg changed=$changed " +
                        "list=${com.nextvm.core.model.PathSpoofConfig.exemptList()} " +
                        "（重启容器/guest 后生效）"
                )
            }

            ACTION_SPOOF_EXEMPT_REMOVE -> {
                val pkg = intent.getStringExtra("pkg")
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "SPOOF_EXEMPT_REMOVE: missing --es pkg")
                    return
                }
                val changed = com.nextvm.core.model.PathSpoofConfig.setGuestExempt(pkg, false)
                Log.i(
                    TAG,
                    "SPOOF_EXEMPT_REMOVE $pkg changed=$changed " +
                        "list=${com.nextvm.core.model.PathSpoofConfig.exemptList()} " +
                        "（重启容器/guest 后生效）"
                )
            }

            ACTION_SPOOF_EXEMPT_LIST -> {
                val list = com.nextvm.core.model.PathSpoofConfig.exemptList()
                Log.i(
                    TAG,
                    "SPOOF_EXEMPT_LIST count=${list.size} " +
                        "loaded=${com.nextvm.core.model.PathSpoofConfig.isLoaded()}"
                )
                list.forEach { Log.i(TAG, "SPOOF_EXEMPT pkg=$it") }
            }

            ACTION_SELF_CHECK -> {
                val line = try {
                    engine.selfCheck()
                } catch (t: Throwable) {
                    "hookcheck failed ${t.javaClass.simpleName}: ${t.message}"
                }
                Log.i(TAG, "SELF_CHECK -> $line")
            }

            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    /**
     * 把文件内容分块 base64 打到 logcat。
     *
     * 格式与探针的 `REPORT v1 part i/n` 同构，[tools/extract-report.py] 的分块还原逻辑
     * 可以直接复用；`end` 行带 sha256，便于校验完整性。
     */
    private fun emitOutChunks(instance: String, name: String, bytes: ByteArray) {
        // logcat 单行上限约 4076 字符（LOGGER_ENTRY_MAX_PAYLOAD）。
        // 原始 3KB -> base64 4096 字符会被截断（实测 part 长度 4033，不是 4 的倍数）。
        // 取 2KB -> base64 2732 字符，稳在限额内。
        val chunkSize = 2 * 1024
        val total = if (bytes.isEmpty()) 0 else (bytes.size + chunkSize - 1) / chunkSize
        for (i in 0 until total) {
            val from = i * chunkSize
            val to = minOf(from + chunkSize, bytes.size)
            val b64 = Base64.encodeToString(bytes.copyOfRange(from, to), Base64.NO_WRAP)
            Log.i(TAG, "OUTCHUNK v1 part ${i + 1}/$total $b64")
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update(bytes)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            "?"
        }
        Log.i(
            TAG,
            "OUTCHUNK v1 end instance=$instance name=$name parts=$total bytes=${bytes.size} sha=$sha"
        )
    }

    companion object {
        private const val TAG = "CmdReceiver"
        const val ACTION_INSTALL = "com.nextvm.cmd.INSTALL"
        const val ACTION_LAUNCH = "com.nextvm.cmd.LAUNCH"
        const val ACTION_LAUNCH_ACTIVITY = "com.nextvm.cmd.LAUNCH_ACTIVITY"
        const val ACTION_UNINSTALL = "com.nextvm.cmd.UNINSTALL"
        const val ACTION_LIST = "com.nextvm.cmd.LIST"

        // --- 探针输出导出（P0-2）---
        const val ACTION_COLLECT_OUT = "com.nextvm.cmd.COLLECT_OUT"
        const val ACTION_LIST_OUT = "com.nextvm.cmd.LIST_OUT"
        const val ACTION_CAT_OUT = "com.nextvm.cmd.CAT_OUT"

        // --- 路径欺骗豁免（2026-11-25）---
        // 豁免 = 不做任何路径形态欺骗（不产生合成路径），但仍隔离到沙箱
        // 注意：改完需重启容器/guest 才生效
        const val ACTION_SPOOF_EXEMPT_ADD = "com.nextvm.cmd.SPOOF_EXEMPT_ADD"
        const val ACTION_SPOOF_EXEMPT_REMOVE = "com.nextvm.cmd.SPOOF_EXEMPT_REMOVE"
        const val ACTION_SPOOF_EXEMPT_LIST = "com.nextvm.cmd.SPOOF_EXEMPT_LIST"

        // --- 容器自检（2026-11-25）---
        // 主动执行：临时规则命中/移除、写入落沙箱、未知路径不误改、诊断通道可写、配置可读
        const val ACTION_SELF_CHECK = "com.nextvm.cmd.SELF_CHECK"
    }
}
