package fr.thesdupalais.presentation.articles

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import fr.thesdupalais.data.database.ArticleImageStore
import java.io.ByteArrayInputStream
import java.util.UUID

@Composable
internal fun ArticleMarkdownWebView(
    articleId: UUID,
    markdown: String,
    imageStore: ArticleImageStore,
    onWebViewPositioned: (LayoutCoordinates) -> Unit,
    onTocTarget: (Float) -> Unit,
) {
    val context = LocalContext.current
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val border = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val background = MaterialTheme.colorScheme.background.toArgb()
    val transformed = remember(articleId, markdown) {
        ArticlePackageImages.toAppUrls(articleId, markdown)
    }
    val document = remember(transformed) { RichArticleMarkdown.render(transformed) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var heightCssPx by remember { mutableIntStateOf(100) }
    var enlargedName by remember { mutableStateOf<String?>(null) }
    val html = remember(document, foreground, secondary, border, background) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
            html,body{background:#${background.hex()}}body{margin:0;padding:0;color:#${foreground.hex()};font-family:sans-serif;font-size:16px;line-height:1.55;overflow-wrap:anywhere}
            h1,h2,h3{line-height:1.25;margin:1.15em 0 .55em} h1{font-size:1.55em} h2{font-size:1.35em} h3{font-size:1.15em}
            p,ul,ol,blockquote{margin:.55em 0} blockquote{margin-left:0;padding-left:12px;border-left:3px solid #${border.hex()};color:#${secondary.hex()}}
            a{color:#${foreground.hex()};text-decoration:underline} hr{border:0;border-top:1px solid #${border.hex()};margin:1.2em 0}
            .table-wrap{max-width:100%;overflow-x:auto}table{border-collapse:collapse;min-width:max-content;max-width:100%;font-size:1em}th,td{border:1px solid #${border.hex()};padding:7px 9px;white-space:normal}th{background:rgba(127,127,127,.16)}
            img{max-width:100%;height:auto;border-radius:8px}figure{margin:12px 0}figcaption{color:#${secondary.hex()};font-size:.9em}
            input[type=checkbox]{pointer-events:none;accent-color:#${secondary.hex()}}
            </style></head><body>${document.html.replace("<table>", "<div class=\"table-wrap\"><table>").replace("</table>", "</table></div>").replace("<img ", "<img onclick=\"location.href='apparticle-view://image/'+this.src.split('/').pop()\" onerror=\"this.replaceWith(document.createTextNode(this.alt||'Image indisponible'))\" ").replace(Regex("<img([^>]*?) title=\\\"([^\\\"]*)\\\"([^>]*)>")) { "<figure><img${it.groupValues[1]}${it.groupValues[3]}><figcaption>${it.groupValues[2]}</figcaption></figure>" }}</body></html>"""
    }

    Column(Modifier.fillMaxWidth()) {
        if (document.toc.isNotEmpty()) {
            Text("Sommaire", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            document.toc.forEach { entry ->
                TextButton(onClick = {
                    val anchor = entry.anchor
                    webView?.evaluateJavascript("(function(){var e=document.getElementById('$anchor');return e?Math.round(e.getBoundingClientRect().top+window.scrollY):-1})()") { value ->
                        value?.trim('"')?.toFloatOrNull()?.takeIf { it >= 0f }?.let(onTocTarget)
                    }
                }, modifier = Modifier.padding(start = ((entry.level - 2) * 12).dp)) {
                    Text(entry.title, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth()
                // WebView scrollHeight is in CSS px; with the viewport meta tag, one CSS px equals one dp.
                // Dividing by Android density made the WebView too short and left a competing inner vertical scroll.
                .height(articleDocumentHeightDp(heightCssPx).dp)
                .onGloballyPositioned(onWebViewPositioned)
                .testTag("article-markdown-webview"),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(background)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    settings.javaScriptEnabled = true // Only used for image click and height measurement in generated HTML.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // HTML is generated locally. Delegate every main frame to WebView;
                            // only subresources pass through the strict apparticle image filter.
                            if (shouldDelegateArticleMainFrame(request.isForMainFrame)) return null
                            val uri = request.url
                            if (uri.scheme != "apparticle") return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            val parts = uri.pathSegments
                            if (uri.host != "image" || parts.size != 2 || parts[0] != articleId.toString()) return WebResourceResponse("text/plain", "utf-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            val image = runCatching { imageStore.file(articleId.toString(), parts[1]) }.getOrNull()
                                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            if (!image.isFile || image.length() > ArticleImageStore.MAX_OUTPUT_BYTES) return WebResourceResponse("text/plain", "utf-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            return runCatching { WebResourceResponse("image/jpeg", null, image.inputStream()) }.getOrNull()
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme == "apparticle-view" && uri.host == "image") {
                                enlargedName = uri.lastPathSegment
                                return true
                            }
                            if (uri.scheme == "about" || uri.scheme == "#") return false
                            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return true
                            return try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); true } catch (_: Exception) { true }
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("document.documentElement.scrollHeight.toString()") { value -> value?.trim('"')?.toIntOrNull()?.let { heightCssPx = it } }
                        }
                    }
                    webView = this
                    loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                view.setBackgroundColor(background)
                if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) }
            },
        )
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }
    enlargedName?.let { filename ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { enlargedName = null }) {
            AndroidView(factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (shouldDelegateArticleMainFrame(request.isForMainFrame)) return null
                            val uri = request.url
                            if (uri.scheme != "apparticle" || uri.host != "image" || uri.pathSegments != listOf(articleId.toString(), filename)) {
                                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }
                            val file = runCatching { imageStore.file(articleId.toString(), filename) }.getOrNull()?.takeIf { it.isFile }
                                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            return WebResourceResponse("image/jpeg", null, file.inputStream())
                        }
                    }
                    loadDataWithBaseURL("about:blank", "<meta name=viewport content='width=device-width,initial-scale=1'><body style='margin:0;background:#111;display:grid;place-items:center;height:100vh'><img style='max-width:100vw;max-height:100vh;object-fit:contain' src='apparticle://image/$articleId/$filename'></body>", "text/html", "UTF-8", null)
                }
            }, modifier = Modifier.fillMaxWidth().height(600.dp))
        }
    }
}

private fun Int.hex() = toLong().and(0xFFFFFF).toString(16).padStart(6, '0')

internal fun shouldDelegateArticleMainFrame(isForMainFrame: Boolean): Boolean = isForMainFrame

internal fun articleDocumentHeightDp(documentHeightCssPx: Int): Float =
    documentHeightCssPx.coerceAtLeast(100).toFloat()

private object ArticlePackageImages {
    private val imageReference = Regex("!\\[([^\\]]*)]\\(<?images/([0-9a-fA-F-]{36}\\.jpg)>?(\\s+\"[^\"]*\")?\\)")
    fun toAppUrls(articleId: UUID, markdown: String): String = imageReference.replace(markdown) { m ->
        "![${m.groupValues[1]}](apparticle://image/$articleId/${m.groupValues[2]}${m.groupValues[3]})"
    }
}
