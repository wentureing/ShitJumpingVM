plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nextvm.core.common"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.all {
            it.testLogging { showStandardStreams = true }
            it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hiddenapibypass)

    // 诊断抽象层的契约测试（纯 JVM）
    testImplementation(libs.junit)
}
