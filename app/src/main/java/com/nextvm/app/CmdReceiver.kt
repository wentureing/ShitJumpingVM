package com.nextvm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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

            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    companion object {
        private const val TAG = "CmdReceiver"
        const val ACTION_INSTALL = "com.nextvm.cmd.INSTALL"
        const val ACTION_LAUNCH = "com.nextvm.cmd.LAUNCH"
        const val ACTION_LAUNCH_ACTIVITY = "com.nextvm.cmd.LAUNCH_ACTIVITY"
        const val ACTION_UNINSTALL = "com.nextvm.cmd.UNINSTALL"
        const val ACTION_LIST = "com.nextvm.cmd.LIST"
    }
}
