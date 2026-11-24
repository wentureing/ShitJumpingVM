package com.nextvm.core.virtualization.engine.activitythread

import android.content.Intent
import android.os.Message
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.DebugTrace
import com.nextvm.core.common.findField
import com.nextvm.core.model.VirtualIntentExtras
import timber.log.Timber

/**
 * 旧版 / 非 Activity 消息的处理（v151 从 `ActivityThreadHook.kt` 拆出）。
 *
 * ## 职责
 * 处理 `ActivityThread.mH` 里除 `EXECUTE_TRANSACTION` 之外的三种消息：
 * - `LAUNCH_ACTIVITY`(100)：Android 9 以下的老路径；
 * - `CREATE_SERVICE`(114)：把 stub Service 换成 guest 真实 Service；
 * - `RECEIVER`(113)：把 stub Receiver 换成 guest 真实 Receiver。
 *
 * 三者做法一致：从 `msg.obj` 里取出 framework 的数据对象（反射），把其中的
 * `name` / `packageName` / `applicationInfo.*` 换成 guest 的值，然后调
 * [ActivityThreadHook.replaceClassLoader] 换 classloader。
 *
 * ## 不负责
 * - ❌ Activity 的生命周期事务（`EXECUTE_TRANSACTION` → `TransactionInterceptor`）
 * - ❌ LoadedApk 重建 / 资源注入（`GuestClassLoaderSetup` / `VirtualResourceManager`）
 * - ❌ 决定"要不要接管"（`ActivityThreadHook.handleMessage` 分发）
 *
 * ## 偏差登记
 *
 * | # | 偏差 | 类型 | 说明 |
 * | --- | --- | --- | --- |
 * | 1 | 反射改写 framework 私有字段（`intent` / `info` / `applicationInfo`） | ✅ **必要偏差** | 没有公开 API 能把 stub 组件替换成 guest 组件；字段名随 OS 版本变化时**降级为不接管**（`return false`），不会崩 |
 * | 2 | 三个 handler 都 `return false`（让 original 继续处理） | 🟡 **待确认** | 与 `handleMessage` 的返回值语义有关：当前 `Callback` 包装忽略返回值，所以 `return false` 不影响结果；但若将来修好"返回值生效"，这里要重新定义（我们只做"替换"、不接管执行） |
 * | 3 | `LAUNCH_ACTIVITY` 在 Android 9+ 直接 `return false` | ✅ 必要 | 9+ 走 `EXECUTE_TRANSACTION`；保留这段是为了兼容老设备 |
 *
 * ## 约束与坑
 * 1. **所有反射都可能失败**（字段改名）→ 每个 handler 都 try/catch，失败只记日志并 `return false`，
 *    **不能抛**（否则会把 `mH` 的 Callback 打断）。
 * 2. 替换完必须调 `replaceClassLoader`，否则类还是从 stub 的 classloader 解析 → `ClassNotFoundException`。
 * 3. Service/Receiver 的 `applicationInfo.dataDir` 只有 Service 分支设置了（Receiver 没设）——
 *    这是历史行为，**改动前先确认是否有依赖**。
 * 4. debug 诊断：入口、命中替换、失败 都会写 `debug-trace.log`（tag = `legacy`）。
 */
private const val TAG_LEGACY = "ATHook"

/** `LAUNCH_ACTIVITY`(100)：Android 9 以下的 Activity 启动路径。 */
internal fun ActivityThreadHook.handleLegacyLaunchActivity(msg: Message): Boolean {
    if (AndroidCompat.isAtLeastP) return false // Android 9+ 走 EXECUTE_TRANSACTION

    try {
        val record = msg.obj ?: return false
        val intentField = findField(record::class.java, "intent")
        intentField?.isAccessible = true
        val intent = intentField?.get(record) as? Intent ?: return false

        val targetActivity = intent.getStringExtra(VirtualIntentExtras.TARGET_ACTIVITY)
            ?: return false

        val activityInfoField = findField(record::class.java, "activityInfo")
        activityInfoField?.isAccessible = true
        val activityInfo = activityInfoField?.get(record)

        if (activityInfo != null) {
            val nameField = findField(activityInfo::class.java, "name")
            nameField?.isAccessible = true
            nameField?.set(activityInfo, targetActivity)
        }

        DebugTrace.event("legacy", "LAUNCH_ACTIVITY swapped -> $targetActivity")
        Timber.tag(TAG_LEGACY).d("Legacy class swap: $targetActivity")
    } catch (e: Exception) {
        DebugTrace.fail("legacy", "LAUNCH_ACTIVITY handler failed", e)
        Timber.tag(TAG_LEGACY).e(e, "Error in legacy LAUNCH_ACTIVITY handler")
    }

    return false
}

/**
 * `CREATE_SERVICE`(114) — Service 创建拦截。
 *
 * `msg.obj` = `CreateServiceData`，含 `token` / `info`(ServiceInfo) / `compatInfo` / `intent`。
 */
internal fun ActivityThreadHook.handleCreateService(msg: Message): Boolean {
    val data = msg.obj ?: return false

    try {
        val dataClass = data::class.java

        val infoField = findField(dataClass, "info")
        infoField?.isAccessible = true
        val serviceInfo = infoField?.get(data) ?: return false

        val intentField = findField(dataClass, "intent")
        intentField?.isAccessible = true
        val intent = intentField?.get(data) as? Intent

        val targetPackage = intent?.getStringExtra(VirtualIntentExtras.TARGET_PACKAGE)
        val targetService = intent?.getStringExtra("_nextvm_target_service")
        val instanceId = intent?.getStringExtra(VirtualIntentExtras.INSTANCE_ID)
        val apkPath = intent?.getStringExtra(VirtualIntentExtras.APK_PATH)

        if (targetPackage != null && targetService != null && instanceId != null && apkPath != null) {
            DebugTrace.event("legacy", "CREATE_SERVICE swapping -> $targetService ($targetPackage)")
            Timber.tag(TAG_LEGACY).i(
                "Intercepted stub service → swapping to $targetService ($targetPackage)"
            )

            val siClass = serviceInfo::class.java

            val nameField2 = findField(siClass, "name")
            nameField2?.isAccessible = true
            nameField2?.set(serviceInfo, targetService)

            val pkgField = findField(siClass, "packageName")
            pkgField?.isAccessible = true
            pkgField?.set(serviceInfo, targetPackage)

            val appInfoField = findField(siClass, "applicationInfo")
            appInfoField?.isAccessible = true
            val appInfo = appInfoField?.get(serviceInfo)
            if (appInfo != null) {
                val aiPkgField = findField(appInfo::class.java, "packageName")
                aiPkgField?.isAccessible = true
                aiPkgField?.set(appInfo, targetPackage)

                val srcDirField = findField(appInfo::class.java, "sourceDir")
                srcDirField?.isAccessible = true
                srcDirField?.set(appInfo, apkPath)

                val pubSrcDirField = findField(appInfo::class.java, "publicSourceDir")
                pubSrcDirField?.isAccessible = true
                pubSrcDirField?.set(appInfo, apkPath)

                val dataDirStr = engine.getDataDir(instanceId)
                if (dataDirStr != null) {
                    val ddField = findField(appInfo::class.java, "dataDir")
                    ddField?.isAccessible = true
                    ddField?.set(appInfo, dataDirStr)
                }
            }

            replaceClassLoader(targetPackage, instanceId, apkPath)

            DebugTrace.event(
                "legacy", "CREATE_SERVICE swap complete: $targetService (instance=$instanceId)"
            )
            Timber.tag(TAG_LEGACY).d(
                "Service class swap complete: $targetService (instance: $instanceId)"
            )
        }
    } catch (e: Exception) {
        DebugTrace.fail("legacy", "CREATE_SERVICE handler failed", e)
        Timber.tag(TAG_LEGACY).e(e, "Error in CREATE_SERVICE handler")
    }

    return false
}

/**
 * `RECEIVER`(113) — BroadcastReceiver 分发拦截。
 *
 * `msg.obj` = `ReceiverData`，含 `intent` / `info`(ActivityInfo，名字容易混淆) / `compatInfo`。
 */
internal fun ActivityThreadHook.handleReceiver(msg: Message): Boolean {
    val data = msg.obj ?: return false

    try {
        val dataClass = data::class.java

        val intentField = findField(dataClass, "intent")
        intentField?.isAccessible = true
        val intent = intentField?.get(data) as? Intent ?: return false

        val targetPackage = intent.getStringExtra(VirtualIntentExtras.TARGET_PACKAGE)
        val targetReceiver = intent.getStringExtra("_nextvm_target_receiver")
        val instanceId = intent.getStringExtra(VirtualIntentExtras.INSTANCE_ID)
        val apkPath = intent.getStringExtra(VirtualIntentExtras.APK_PATH)

        if (targetPackage != null && targetReceiver != null && instanceId != null && apkPath != null) {
            DebugTrace.event("legacy", "RECEIVER swapping -> $targetReceiver ($targetPackage)")
            Timber.tag(TAG_LEGACY).i(
                "Intercepted stub receiver → swapping to $targetReceiver ($targetPackage)"
            )

            val infoField = findField(dataClass, "info")
            infoField?.isAccessible = true
            val receiverInfo = infoField?.get(data)

            if (receiverInfo != null) {
                val riClass = receiverInfo::class.java

                val nameField2 = findField(riClass, "name")
                nameField2?.isAccessible = true
                nameField2?.set(receiverInfo, targetReceiver)

                val pkgField = findField(riClass, "packageName")
                pkgField?.isAccessible = true
                pkgField?.set(receiverInfo, targetPackage)

                val appInfoField = findField(riClass, "applicationInfo")
                appInfoField?.isAccessible = true
                val appInfo = appInfoField?.get(receiverInfo)
                if (appInfo != null) {
                    val aiPkgField = findField(appInfo::class.java, "packageName")
                    aiPkgField?.isAccessible = true
                    aiPkgField?.set(appInfo, targetPackage)

                    val srcDirField = findField(appInfo::class.java, "sourceDir")
                    srcDirField?.isAccessible = true
                    srcDirField?.set(appInfo, apkPath)

                    val pubSrcDirField = findField(appInfo::class.java, "publicSourceDir")
                    pubSrcDirField?.isAccessible = true
                    pubSrcDirField?.set(appInfo, apkPath)
                }
            }

            replaceClassLoader(targetPackage, instanceId, apkPath)

            DebugTrace.event(
                "legacy", "RECEIVER swap complete: $targetReceiver (instance=$instanceId)"
            )
            Timber.tag(TAG_LEGACY).d(
                "Receiver class swap complete: $targetReceiver (instance: $instanceId)"
            )
        }
    } catch (e: Exception) {
        DebugTrace.fail("legacy", "RECEIVER handler failed", e)
        Timber.tag(TAG_LEGACY).e(e, "Error in RECEIVER handler")
    }

    return false
}
