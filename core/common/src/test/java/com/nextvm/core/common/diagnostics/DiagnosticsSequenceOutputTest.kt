package com.nextvm.core.common.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用新日志系统产出一段**完整的验证序列**并打印到 stdout（CI 日志可见）。
 *
 * 目的：
 *   1) 证明 Diagnostics + RecordingSink 能端到端产出 golden 格式
 *   2) 给出三条关键路径的**期望输出模板**，供真机首轮对照
 *   3) 这段输出就是将来 checks-golden 的雏形
 *
 * 注意：这里的值是按代码逻辑推导的**期望值**，不是真机实测值。
 * 真机跑出来的若与此不同，差异即为要查的点。
 * 详见 `docs/设计/设计-诊断输出与golden验证.md`。
 */
class DiagnosticsSequenceOutputTest {

    @Test
    fun `输出三条关键路径的验证序列模板`() {
        val sink = RecordingSink()
        val d = DiagnosticsRouter(listOf(sink))

        // ---- native 安装（Phase 1/2）----
        d.check(CheckResult(
            "native", "phase1", "javaIoHooks", CheckState.OK,
            "libcore.io.Linux", "unchanged_default_off", "unchanged_default_off",
            reason = null
        ))
        d.check(CheckResult(
            "native", "phase2", "loadNativeLibrary", CheckState.OK,
            "libnextvm.so", "loaded", "loaded"
        ))
        d.check(CheckResult(
            "native", "phase2", "nativeInit", CheckState.OK,
            "GOT patches", "installed", "installed",
            mapOf("libs" to "3")
        ))
        d.check(CheckResult(
            "native", "phase2", "dataRoot", CheckState.OK,
            "native prefix/root", "set", "set",
            linkedMapOf("prefix" to "<HOST_DATA>", "root" to "<ROOT>")
        ))

        // ---- 规则注册（native 生效的前提）----
        d.check(CheckResult(
            "path", "v142", "registerRules", CheckState.OK,
            "g_path_redirects", "kotlin=11 native=11", "kotlin=11 native=11",
            linkedMapOf("pkg" to "mark.via", "appDir" to "<ROOT>/data/1")
        ))

        // ---- Application 启动（Step 1..6）----
        val appSteps = listOf(
            Triple("1", "buildApplicationInfo", "ApplicationInfo"),
            Triple("2", "createApplication", "Application"),
            Triple("3", "createRecord", "ApplicationRecord"),
            Triple("3.5", "setGuestApplication", "VirtualContext.mApplication"),
            Triple("4", "attachBaseContext", "Context"),
            Triple("4.5", "patchLoadedApkApplication", "LoadedApk.mApplication"),
            Triple("5", "initContentProviders", "ContentProvider[]")
        )
        for ((step, name, target) in appSteps) {
            d.check(CheckResult(
                "application", step, name, CheckState.OK, target, "done", "done"
            ))
        }
        d.check(CheckResult(
            "application", "6", "applicationOnCreate", CheckState.OK,
            "Application.onCreate", "called", "called",
            mapOf("cls" to "mark.via.App")
        ))

        // ---- Activity 启动（STEP 0..5）----
        val activitySteps = listOf(
            Triple("0", "preRegisterToken", "mActivities"),
            Triple("1", "swapActivityName", "ActivityInfo.name"),
            Triple("1.5", "breadcrumbWitness", "breadcrumb"),
            Triple("2", "replaceClassLoader", "LoadedApk.mClassLoader"),
            Triple("2.1", "resourceProbe", "Resources"),
            Triple("2.2", "apkWatchdog", "apk file"),
            Triple("2.5", "bootApplication", "Application"),
            Triple("3", "pendingResourceInject", "pending"),
            Triple("4", "pendingContextWrap", "pending"),
            Triple("5", "instrumentationHook", "Instrumentation")
        )
        for ((step, name, target) in activitySteps) {
            d.check(CheckResult(
                "activity", step, name, CheckState.OK, target, "done", "done"
            ))
        }

        val dump = sink.dump()
        // CI 日志里直接可见
        println("===== DIAGNOSTICS SEQUENCE (template) =====")
        println(dump)
        println("===== END =====")

        // 结构断言：21 条 check、errors 段为空
        assertTrue("check 数量应为 21，实际=${sink.checks.size}", sink.checks.size == 21)
        assertTrue("errors 段必须为空", sink.errorLines.isEmpty())
        assertTrue("序号应连续", sink.checks.first().startsWith("0001 "))
        assertTrue("末条序号应为 0021", sink.checks.last().startsWith("0021 "))
    }
}
