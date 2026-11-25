plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nextvm.core.common"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    // v145：开启 BuildConfig，让 DebugFlags 能用 `BuildConfig.DEBUG` 做**编译期总闸**。
    // release variant 里它是 `false` 字面量，R8 会把所有调试分支整段删除
    // （不写文件、不读 property、不反射）—— 比运行时读 FLAG_DEBUGGABLE 更彻底。
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hiddenapibypass)

    // 诊断抽象层的契约测试（纯 JVM，不依赖 Android）
    testImplementation(libs.junit)
}
