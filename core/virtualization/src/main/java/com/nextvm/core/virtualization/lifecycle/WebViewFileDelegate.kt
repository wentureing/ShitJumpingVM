package com.nextvm.core.virtualization.lifecycle

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * v137-webview-file：给 guest 的 WebView 代读 `file://`，并给正确的 MIME。
 *
 * ## 为什么需要
 *
 * 实测（探针 W2/W3/W17/W20 + Gaia 对照，见
 * docs/records/记录-WebView根因定位-W14沙箱路径.md 与
 * docs/records/记录-Gaia内WebView对照-W2正常与容器差异.md）：
 *
 * - 容器内 `file://` 加载 guest 自己写的 `.html`，`document.contentType` 是 `text/plain`
 *   （内容对上、MIME 错），页面被当源码显示；Via 主页正好是这条路。
 * - 把内容改成 `GIF89a` 开头，MIME 变成 `image/gif` —— 说明 MIME 来自**内容嗅探**，
 *   即 chromium Java 侧 AndroidProtocolHandler.getMimeType 的兜底
 *   `URLConnection.guessContentTypeFromStream`（HTML 没有魔数 -> null -> 默认 text/plain）。
 * - `file:///android_asset/` 在容器内直接打不开（`Unable to open asset URL`）。
 * - 同一台设备上 Gaia（路径保真路线）两者都正常 —— 说明这不是平台行为，是我们容器的缺陷。
 *
 * 按 docs/架构/架构-代理范式.md 的结论：
 *   - 「guest 读 WebView 的 file:/ -> 只能代读，且需找注入点」（§五）
 *   - 元数据（MIME）可以造假，字节流必须有真实载体（FileInputStream / assets 流）
 *   - 「查询类造假几乎无敌」，所以补 MIME 属于范式中最安全的一类
 *
 * 因此这里做的是：**不再依赖 open hook 的偶然放行 + chromium 的嗅探**，
 * 而是在 Java 层的可控点（shouldInterceptRequest）把 guest 的 file:// 请求
 * 变成「容器代读 + 按扩展名给 MIME」。
 *
 * ## 与 Via 的兼容性
 *
 * Via 自己的 client（反编译确认）只对 `homepage.css` 返回响应，其余返回 null。
 * 我们的顺序是「先问应用，应用返回非 null 就原样交回」-> 与 Via 零冲突，
 * 它补 text/css 的那条仍然由它自己完成。
 *
 * ## 硬规则（性能/功能/暴露三条线）
 *
 * 1. **只接管白名单扩展名**（文本 + 矢量/小图）。媒体、PDF、压缩包一律返回 null，
 *    交给原生 —— 原生 file loader 支持 Range，我们返回整条流不支持 seek，
 *    接管 .mp4/.pdf 会破坏拖动。网络视频（m3u8/mp4 直链/DASH）是 http，压根不经过这里。
 * 2. **任何异常 / 文件不存在 / MIME 查不到 -> return null**，退回原生行为。
 *    保证「最差等于现状」，绝不比现在更坏（尤其是保存版网页里的 woff2/wasm 等新类型）。
 * 3. **不碰系统路径**（/system、/apex、/vendor、/data/app、/product）。
 *
 * ## 已知代价（写在这里，避免以后当成 bug 找）
 *
 * - `WebView.getWebViewClient()` 会返回本 DelegateClient 而不是应用原来设的那个。
 *   Via 这类浏览器不会去断言 client 身份；将来若导入会做反注入检测的 App
 *   （金融/政务），用 VirtualConstants.webviewFileDelegateEnabled() 关掉即可。
 * - 应用如果在我们之后又 setWebViewClient，包装会被顶掉；v124 定时器每 5 秒
 *   复查一次，会自动重新包装（窗口期内仍是现状行为）。
 */
object WebViewFileDelegate {

    private const val TAG = "v137-webview-file"

    /** 只接管这些扩展名（文本 + 矢量/小图）。媒体/PDF/压缩包刻意不在此列。 */
    private val TAKE_OVER: Set<String> = setOf(
        "html", "htm", "xhtml", "css", "js", "mjs", "json",
        "svg", "txt", "xml", "webmanifest", "ico"
    )

    /** MimeTypeMap 在部分 ROM 上缺项的兜底（只补 Web 常用、原生会给错的） */
    private val MIME_FALLBACK: Map<String, String> = mapOf(
        "svg" to "image/svg+xml",
        "mjs" to "text/javascript",
        "json" to "application/json",
        "webmanifest" to "application/manifest+json",
        "ico" to "image/x-icon"
    )

    /** 明确不碰的路径前缀：系统与安装区，不属于 guest 沙箱 */
    private val SKIP_PREFIXES = arrayOf(
        "/system/", "/apex/", "/vendor/", "/data/app/", "/product/", "/data/dalvik-cache/"
    )

    /** 已包装过的「应用原 client」实例，避免同一个对象被包两层 */
    private val wrappedOriginals: MutableSet<WebViewClient> =
        Collections.newSetFromMap<WebViewClient>(WeakHashMap())

    @Volatile
    private var installedCount = 0

    @Volatile
    private var delegatedCount = 0

    /** 只在数字变化时写 breadcrumb，避免每次请求都写文件 */
    @Volatile
    private var lastReported = ""

    private fun enabled(): Boolean = try {
        com.nextvm.core.model.VirtualConstants.webviewFileDelegateEnabled()
    } catch (t: Throwable) {
        true
    }

    /**
     * 遍历视图树，给找到的 WebView 装/复核代读 client。
     * 只做读操作 + 一次 setWebViewClient，绝不抛到 guest。
     */
    fun scan(root: View?) {
        if (root == null) return
        if (!enabled()) return
        try {
            val found = ArrayList<WebView>(2)
            collect(root, found, 0)
            for (wv in found) install(wv)
            reportIfChanged()
        } catch (t: Throwable) {
            // never disturb the guest
        }
    }

    private fun collect(v: View, out: MutableList<WebView>, depth: Int) {
        if (depth > 80) return
        if (v is WebView) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = try {
                    v.getChildAt(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                collect(c, out, depth + 1)
            }
        }
    }

    private fun install(wv: WebView) {
        try {
            val cur = currentClient(wv) ?: return   // 应用没设 client：不动，减少侵入
            if (cur is DelegateClient) return       // 已经是我们这层
            if (!wrappedOriginals.add(cur)) return  // 这个原实例包过了（应用重复 set 同一对象）
            wv.setWebViewClient(DelegateClient(cur))
            installedCount++
        } catch (t: Throwable) {
            // never disturb the guest
        }
    }

    /**
     * 取当前的 WebViewClient，三级 fallback：
     *   1) `getWebViewClient()`（反射，部分 ROM 是 hide）
     *   2) 按字段名 `mWebViewClient`
     *   3) 放弃
     *
     * 注意：探针 W30 试过「按类型扫 WebView.declaredFields」，在 Android 15 上**找不到**
     * （client 存在 provider 内部）。所以这里优先走方法，字段名只作兜底。
     */
    private fun currentClient(wv: WebView): WebViewClient? {
        try {
            val m = WebView::class.java.getMethod("getWebViewClient")
            val v = m.invoke(wv)
            if (v is WebViewClient) return v
        } catch (t: Throwable) {
            // fallthrough
        }
        for (name in arrayOf("mWebViewClient", "mClient")) {
            try {
                val f: Field = fieldInHierarchy(WebView::class.java, name)
                f.isAccessible = true
                val v = f.get(wv)
                if (v is WebViewClient) return v
            } catch (t: Throwable) {
                // try next
            }
        }
        return null
    }

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun reportIfChanged() {
        val snapshot = "$installedCount/$delegatedCount"
        if (snapshot == lastReported) return
        lastReported = snapshot
        GuestBreadcrumb.log(
            TAG + " installed=" + installedCount + " delegated=" + delegatedCount
        )
    }

    // ---------------------------------------------------------------- 接管逻辑

    private fun extOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) "" else
            name.substring(dot + 1).lowercase()
    }

    private fun mimeFor(path: String): String? {
        val ext = extOf(path)
        if (ext.isEmpty()) return null
        try {
            val m = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext)
            if (!m.isNullOrBlank()) return m
        } catch (t: Throwable) {
            // fallthrough
        }
        return MIME_FALLBACK[ext]
    }

    /**
     * 真正的代读。任何不确定都返回 null（退回原生），这是"不比现状更坏"的保证。
     */
    private fun takeOver(wv: WebView, url: String): WebResourceResponse? {
        try {
            val u = Uri.parse(url)
            if (!"file".equals(u.scheme, ignoreCase = true)) return null
            val path = u.path ?: return null

            // asset：guest 的 assets 容器能读（探针 W12：this.getAssets().open 成功）
            if (path.startsWith("/android_asset/")) {
                val rel = path.removePrefix("/android_asset/")
                if (extOf(rel) !in TAKE_OVER) return null
                val mime = mimeFor(rel) ?: return null
                val stream = wv.context.assets.open(
                    rel, android.content.res.AssetManager.ACCESS_STREAMING
                )
                delegatedCount++
                return WebResourceResponse(mime, null, stream)
            }

            for (p in SKIP_PREFIXES) {
                if (path.startsWith(p)) return null
            }
            if (extOf(path) !in TAKE_OVER) return null

            val f = File(path)
            if (!f.isFile) return null
            val mime = mimeFor(path) ?: return null
            delegatedCount++
            return WebResourceResponse(mime, null, FileInputStream(f))
        } catch (t: Throwable) {
            return null   // 出任何问题都退回原生行为
        }
    }

    // ---------------------------------------------------------------- client 包装

    /**
     * 转发型 client：**先问应用**（Via 处理 homepage.css 靠这里），应用返回 null 才接管。
     *
     * 其余覆写方法全部原样转发 —— 这是本方案唯一的回归风险点：漏转发一个应用覆写过
     * 的方法，就等于把那个行为丢掉。所以这里按 API 35 的 WebViewClient 公开方法
     * 逐个转发，宁可多写。
     */
    private class DelegateClient(
        private val orig: WebViewClient?
    ) : WebViewClient() {

        private fun safe(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                // 应用的 client 抛异常，不影响我们
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val fromApp = try {
                orig?.shouldInterceptRequest(view, request)
            } catch (t: Throwable) {
                null
            }
            if (fromApp != null) return fromApp
            val u = request.url ?: return null
            return takeOver(view, u.toString())
        }

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(
            view: WebView,
            url: String
        ): WebResourceResponse? {
            val fromApp = try {
                orig?.shouldInterceptRequest(view, url)
            } catch (t: Throwable) {
                null
            }
            if (fromApp != null) return fromApp
            return takeOver(view, url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) =
            safe { orig?.onPageStarted(view, url, favicon) }

        override fun onPageFinished(view: WebView, url: String?) =
            safe { orig?.onPageFinished(view, url) }

        override fun onPageCommitVisible(view: WebView, url: String?) =
            safe { orig?.onPageCommitVisible(view, url) }

        override fun onLoadResource(view: WebView, url: String?) =
            safe { orig?.onLoadResource(view, url) }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
            try {
                orig?.shouldOverrideUrlLoading(view, url) ?: false
            } catch (t: Throwable) {
                false
            }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest?
        ): Boolean = try {
            orig?.shouldOverrideUrlLoading(view, request) ?: false
        } catch (t: Throwable) {
            false
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) = safe { orig?.onReceivedError(view, errorCode, description, failingUrl) }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) = safe { orig?.onReceivedError(view, request, error) }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest?,
            response: WebResourceResponse?
        ) = safe { orig?.onReceivedHttpError(view, request, response) }

        override fun onFormResubmission(
            view: WebView,
            dontResend: android.os.Message?,
            resend: android.os.Message?
        ) = safe { orig?.onFormResubmission(view, dontResend, resend) }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
            safe { orig?.doUpdateVisitedHistory(view, url, isReload) }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler?,
            error: android.net.http.SslError?
        ) = safe { orig?.onReceivedSslError(view, handler, error) }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest?) =
            safe { orig?.onReceivedClientCertRequest(view, request) }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler?,
            host: String?,
            realm: String?
        ) = safe { orig?.onReceivedHttpAuthRequest(view, handler, host, realm) }

        override fun shouldOverrideKeyEvent(view: WebView, event: android.view.KeyEvent?): Boolean =
            try {
                orig?.shouldOverrideKeyEvent(view, event) ?: false
            } catch (t: Throwable) {
                false
            }

        override fun onUnhandledKeyEvent(view: WebView, event: android.view.KeyEvent?) =
            safe { orig?.onUnhandledKeyEvent(view, event) }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
            safe { orig?.onScaleChanged(view, oldScale, newScale) }

        override fun onReceivedLoginRequest(
            view: WebView,
            realm: String?,
            account: String?,
            args: String?
        ) = safe { orig?.onReceivedLoginRequest(view, realm, account, args) }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest?,
            threatType: Int,
            callback: SafeBrowsingResponse?
        ) = safe { orig?.onSafeBrowsingHit(view, request, threatType, callback) }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail?
        ): Boolean = try {
            orig?.onRenderProcessGone(view, detail) ?: false
        } catch (t: Throwable) {
            false
        }
    }
}
