plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nextvm.core.sandbox"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // 让单测失败时把异常栈完整打出来（默认会吞）
            all { it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // --- 单元测试：纯 JVM（HostReadPolicy / PathWhitelistEffect）---
    // 2026-11-25 移除 Robolectric：它的 nativeruntime jar 在 hiltAggregateDeps 阶段
    // transform 失败（container-build 的 Unit tests 步骤因此红），而本模块没有任何测试真正
    // 使用 Robolectric（smoke test 一直是 @Ignore，CI 上下载 android-all 不可靠）。
    // 将来需要 Robolectric 时：加回 testImplementation(libs.robolectric) 与
    // isIncludeAndroidResources = true，并确认 nativeruntime 能正常 transform。
    testImplementation(libs.junit)
}
