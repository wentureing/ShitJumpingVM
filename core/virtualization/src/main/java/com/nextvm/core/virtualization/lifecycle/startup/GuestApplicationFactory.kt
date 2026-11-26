package com.nextvm.core.virtualization.lifecycle.startup

import android.app.Application
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findMethod
import com.nextvm.core.common.runSafe
import timber.log.Timber

/**
 * 造出 guest 的 Application 实例：AppComponentFactory → 反射 → 回退 base Application。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/lifecycle/startup/GuestApplicationFactory.md`
 */
object GuestApplicationFactory {

    private const val TAG = "VAppLifecycle"

    /** className 为空 = AOSP 的默认 Application。 */
    fun classNameOf(applicationClassName: String?): String =
        applicationClassName ?: "android.app.Application"

    fun create(packageName: String, className: String, classLoader: ClassLoader): Application? {
        Timber.tag(TAG).d("Creating Application: $className for $packageName")

        return try {
            // Try AppComponentFactory on Android 9+
            if (AndroidCompat.isAtLeastP) {
                val factoryApp = createViaAppComponentFactory(className, classLoader)
                if (factoryApp != null) {
                    Timber.tag(TAG).d("Application created via AppComponentFactory")
                    return factoryApp
                }
            }

            // Standard reflection path
            val appClass = if (className == "android.app.Application") {
                Application::class.java
            } else {
                classLoader.loadClass(className)
            }

            val app = appClass.getDeclaredConstructor().let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance()
            } as Application

            Timber.tag(TAG).d("Application created via reflection: $className")
            app

        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).w("Application class not found: $className, falling back to base Application")
            try {
                Application::class.java.getDeclaredConstructor().newInstance()
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Failed to create fallback Application")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create Application: $className")
            null
        }
    }

    /**
     * Try to create Application via AppComponentFactory (Android 9+).
     *
     * Real Android uses AppComponentFactory to allow apps to customize
     * instantiation of Application, Activity, Service, etc.
     */
    private fun createViaAppComponentFactory(className: String, classLoader: ClassLoader): Application? {
        return runSafe(TAG) {
            val factoryClass = Class.forName("android.app.AppComponentFactory")
            val factory = factoryClass.getDeclaredConstructor().newInstance()

            val instantiateMethod = findMethod(
                factoryClass,
                "instantiateApplication",
                arrayOf(ClassLoader::class.java, String::class.java)
            )
            instantiateMethod?.isAccessible = true
            instantiateMethod?.invoke(factory, classLoader, className) as? Application
        }
    }
}
