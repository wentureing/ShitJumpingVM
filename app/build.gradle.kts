// Gradle Kotlin DSL 里不能写 java.util.Properties / java.io.File ——
// `java` 会被优先解析成 Gradle 的 JavaPluginExtension（java { } 块），
// 于是 java.util 报 Unresolved reference: util。必须在顶部 import + alias。
import java.io.File as JavaFile
import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nextvm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nextvm.app"
        minSdk = 26
        targetSdk = 35
        // 递增的 versionCode 配合固定 keystore 才能 adb install -r 覆盖
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ===== 签名固定（sandboxsensor，移植自 fork）=====
    // 原项目没有 signingConfigs：AGP 每次构建都用 ~/.android/debug.keystore
    // （CI 里每次新生成）-> 签名漂移 -> adb install -r 必失败 -> 卸载重装
    // -> 丢 appops 授权 + 丢所有沙箱数据。这里显式声明，storeFile 用绝对路径。
    val signingProps = JavaProperties()
    run {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { signingProps.load(it) }
    }
    val ksStore: String? = System.getenv("STORE_FILE_NAME") ?: signingProps.getProperty("STORE_FILE_NAME")
    val ksStorePwd: String? = System.getenv("KEYSTORE_PASSWORD") ?: signingProps.getProperty("KEYSTORE_PASSWORD")
    val ksAlias: String? = System.getenv("STORE_ALIAS") ?: signingProps.getProperty("STORE_ALIAS")
    val ksKeyPwd: String? = System.getenv("KEY_PASSWORD") ?: signingProps.getProperty("KEY_PASSWORD")
    val hasFixedKeystore: Boolean =
        ksStore != null && JavaFile(ksStore).exists() && ksStorePwd != null
    logger.lifecycle(
        "[signing] fixed keystore = " + (if (hasFixedKeystore) "$ksStore (exists)" else "NOT FOUND")
    )

    signingConfigs {
        if (hasFixedKeystore) {
            create("fixedDebug") {
                storeFile = JavaFile(ksStore!!)
                storePassword = ksStorePwd
                keyAlias = ksAlias ?: "androiddebugkey"
                keyPassword = ksKeyPwd ?: ksStorePwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 有固定 keystore 就显式用它（CI 走这条）；本地没有时退回 AGP 默认
            if (hasFixedKeystore) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("correctErrorTypes", "true")
}

dependencies {
    // All modules
    implementation(project(":core:virtualization"))
    implementation(project(":core:model"))
    implementation(project(":core:hook"))
    implementation(project(":core:binder"))
    implementation(project(":core:apk"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:framework"))
    implementation(project(":core:services"))
    implementation(project(":feature:launcher"))
    implementation(project(":feature:appmanager"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:filemanager"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // DI + Nav + Lifecycle
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // Core
    implementation(libs.timber)
    implementation(libs.core.ktx)
}
