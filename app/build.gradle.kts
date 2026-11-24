// Gradle Kotlin DSL 里不能写 java.util.Properties / java.io.File ——
// `java` 会被优先解析成 Gradle 的 JavaPluginExtension（java { } 块），
// 于是 java.util 报 Unresolved reference: util（CI #22 已踩：probe/build.gradle.kts:12）。
// 必须在脚本顶部 import，并用 alias 避开名字冲突。
import java.io.File as JavaFile
import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- 签名凭据读取（参考 C:\Users\86177\Desktop\阅读器开发 的做法）----
//
// 为什么不能依赖 AGP 的默认 debug 签名：
//   AGP 的 debug buildType 在**没有显式 signingConfig** 时，会去
//   `System.getProperty("user.home")/.android/debug.keystore` 找 keystore，
//   找不到就**现场生成一个新的**。
//   而 `System.getProperty("user.home")` 与 shell 的 `$HOME` 在 CI 里可能不一致，
//   于是 workflow 里 `mkdir -p "$HOME/.android" && base64 -d > .../debug.keystore`
//   写的文件 AGP 根本没读到 -> 每次构建都生成新 keystore -> 签名漂移。
//
// 实测证据（三个 APK 的证书 SHA256 全不同，有效期起点就是构建时刻）：
//   fc9a011  60:96:DD:03:80:D3:02:03:07:96:FB:EB:03:81:FE:FA:4D...
//   196fc44  D2:91:D0:F7:48:6B:22:10:3F:06:A4:86:AB:51:F2:F4:1B...
//   c00f1cb  FA:84:99:F3:CC:84:FD:6C:8C:F8:AF:8B:8F:CA:5C:1F:7F...
//   但 CI 里 keytool -list 读到的始终是 60:AD:A6:9E:...（我们注入的那个）
//   => 写进去的和用上的不是同一个文件。
//
// 正确做法：显式声明 signingConfigs，storeFile 用**绝对路径**，不依赖任何推断。
// 凭据来源优先级：CI 环境变量 > local.properties > 无（此时 debug 退回 AGP 默认，
// 并在 CI 里由 "Require fixed keystore" 步骤强制拦截）。
val signingProps = JavaProperties()
run {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { signingProps.load(it) }
    }
}
val ksStore: String? = System.getenv("STORE_FILE_NAME") ?: signingProps.getProperty("STORE_FILE_NAME")
val ksStorePwd: String? = System.getenv("KEYSTORE_PASSWORD") ?: signingProps.getProperty("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("STORE_ALIAS") ?: signingProps.getProperty("STORE_ALIAS")
val ksKeyPwd: String? = System.getenv("KEY_PASSWORD") ?: signingProps.getProperty("KEY_PASSWORD")
val hasFixedKeystore: Boolean =
    ksStore != null && JavaFile(ksStore).exists() && ksStorePwd != null
logger.lifecycle(
    "[signing] fixed keystore = " + (if (hasFixedKeystore) "$ksStore (exists)" else "NOT FOUND") +
        " | user.home=" + System.getProperty("user.home")
)

android {
    namespace = "com.nextvm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nextvm.app"
        minSdk = 26
        targetSdk = 35
        // CI 里用 GITHUB_RUN_NUMBER 单调递增，本地默认 1；
        // 递增的 versionCode 才能让 adb install -r 覆盖安装（配合固定 keystore）。
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // 带上提交短 sha，便于对照 Release 产物文件名。
        versionName = "1.0.0-" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 显式声明签名配置：绝不依赖 AGP 对 ~/.android/debug.keystore 的隐式推断
    signingConfigs {
        if (hasFixedKeystore) {
            create("fixedDebug") {
                storeFile = file(ksStore!!)
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
            // 有固定 keystore 就显式用它（CI 走这条）；
            // 本地没有 local.properties 时退回 AGP 默认 debug 签名。
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
