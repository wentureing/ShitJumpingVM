plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nextvm.core.virtualization"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            // 失败时打印完整异常栈（默认会吞）
            all { it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:hook"))
    implementation(project(":core:binder"))
    implementation(project(":core:apk"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:framework"))
    implementation(project(":core:services"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    implementation(libs.datastore.preferences)
    implementation(libs.hiddenapibypass)

    // Compose runtime (required by kotlin.compose plugin)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)

    // 纯 JVM 单测（不引 Robolectric）：先铆住启动链里的两个纯状态机
    // StubRegistry / VirtualProcessManager，见 docs/计划/计划-黄金测试与覆盖率提升.md
    testImplementation(libs.junit)
}

// 打印每个测试的耗时（CI 定位"哪个类慢"用；本地同样输出，无副作用）。
// 用类型化 TestListener：`afterTest { desc, result -> }` 是 Groovy Closure API，Kotlin DSL 无法推断参数类型。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    addTestListener(object : org.gradle.api.tasks.testing.TestListener {
        // 卡住定位：beforeSuite/beforeTest 先打点，卡死时日志里会出现
        // "有 TESTSTART 但没有对应 TESTTIME" 的那一项。
        override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {
            println("TESTSUITE ${suite.className}")
        }

        override fun afterSuite(
            suite: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult,
        ) {
            println("TESTSUITE-END ${suite.className} ${result.resultType}")
        }

        override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {
            println("TESTSTART ${testDescriptor.className}#${testDescriptor.displayName}")
        }

        override fun afterTest(
            testDescriptor: org.gradle.api.tasks.testing.TestDescriptor,
            result: org.gradle.api.tasks.testing.TestResult,
        ) {
            val ms = result.endTime - result.startTime
            println(
                "TESTTIME ${testDescriptor.className}#${testDescriptor.displayName} " +
                    "${ms}ms ${result.resultType}"
            )
        }
    })
}
