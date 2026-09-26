package app.larpcord

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Makes HTTP requests on behalf of Larpcord's plugins (what GM_xmlhttpRequest does in a
 * userscript manager). Requests carry no WebView cookies, and anything on this phone or
 * the local network is refused, including via redirects, so a page can't use the app
 * to probe it.
 */
object NativeFetch {
    private const val MAX_BODY = 25 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val pool = Executors.newFixedThreadPool(4)

    fun run(request: JSONObject, reply: (JSONObject) -> Unit) = pool.execute {
        val out = JSONObject().put("type", "fetch").put("id", request.optInt("id"))
        try {
            var url = URL(request.getString("url"))
            var method = request.optString("method", "GET").uppercase()
            val rawBody = if (request.isNull("body")) "" else request.optString("body", "")
            var body: ByteArray? = if (rawBody.isEmpty()) null else Base64.decode(rawBody, Base64.DEFAULT)
            val timeout = request.optInt("timeout", 30_000).coerceIn(1_000, 120_000)

            var redirects = 0
            while (true) {
                require(url.protocol == "https" || url.protocol == "http") { "unsupported protocol" }
                if (isLocal(url.host)) throw SecurityException("local addresses are blocked")

                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = false
                conn.requestMethod = method
                request.optJSONObject("headers")?.let { headers ->
                    for (key in headers.keys()) {
                        if (key.equals("host", true) || key.equals("content-length", true)) continue
                        conn.setRequestProperty(key, headers.getString(key))
                    }
                }
                body?.let { bytes ->
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (status in 300..399 && location != null && redirects < MAX_REDIRECTS) {
                    redirects++
                    url = URL(url, location)
                    // Browsers turn a redirected POST into a GET except for 307/308
                    if (status != 307 && status != 308) {
                        method = "GET"
                        body = null
                    }
                    conn.disconnect()
                    continue
                }

                val bytes = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                        if (buffer.size() > MAX_BODY) throw IllegalStateException("response too large")
                    }
                    buffer.toByteArray()
                } ?: ByteArray(0)

                val headerLines = conn.headerFields
                    .filterKeys { it != null }
                    .flatMap { (name, values) -> values.map { "${name.lowercase()}: $it" } }
                    .joinToString("\r\n")

                out.put("status", status)
                    .put("statusText", conn.responseMessage ?: "")
                    .put("headers", headerLines)
                    .put("finalUrl", url.toString())
                    .put("contentType", conn.contentType ?: "")
                    .put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
                conn.disconnect()
                break
            }
        } catch (e: SocketTimeoutException) {
            out.put("error", "timeout")
        } catch (e: Exception) {
            out.put("error", e.message ?: e.javaClass.simpleName)
        }
        reply(out)
    }

    private fun isLocal(host: String): Boolean {
        if (host.equals("localhost", true) || host.endsWith(".local", true)) return true
        return InetAddress.getAllByName(host).any {
            it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress ||
                it.isAnyLocalAddress || it.isMulticastAddress ||
                // IPv6 unique local addresses (fc00::/7) aren't covered by isSiteLocalAddress
                (it.address.size == 16 && (it.address[0].toInt() and 0xFE) == 0xFC)
        }
    }
}
