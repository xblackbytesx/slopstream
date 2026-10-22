package com.xblackbytesx.slopstream.data.importer

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * A one-page HTTP server for getting things onto a TV, where typing is slow and there is often
 * no file picker: open the shown address on a phone or laptop in the same network, pick an
 * export or paste text there. Runs only while its screen is open; accepts one upload at a
 * time, at most [MAX_BODY] bytes, and serves nothing but [page].
 */
class LanImportServer(
    private val context: Context,
    private val page: String = IMPORT_PAGE,
    private val onUpload: suspend (ByteArray) -> String,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Starts listening and returns the address to show, or null without a LAN address. */
    fun start(scope: CoroutineScope): String? {
        val ip = lanAddress() ?: return null
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(ip, PORTS.firstOrNull { p -> runCatching { ServerSocket(p).close() }.isSuccess } ?: 0))
        }
        server = socket
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try { socket.accept() } catch (e: SocketException) { break }
                client.use { handle(it) }
            }
        }
        return "http://${ip.hostAddress}:${socket.localPort}"
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
        server = null
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page)
            method == "POST" && path == "/upload" -> {
                val length = headers["content-length"]?.toLongOrNull() ?: -1
                if (length !in 1..MAX_BODY) return respond(out, 413, "text/plain; charset=utf-8", "Too large or empty")
                val body = ByteArray(length.toInt())
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val message = runCatching { onUpload(body.copyOf(read)) }.getOrElse { "Import failed: ${it.message}" }
                respond(out, 200, "text/plain; charset=utf-8", message)
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Payload Too Large" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    private fun lanAddress(): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    companion object {
        private const val MAX_BODY = 50L * 1024 * 1024
        private val PORTS = listOf(8765, 8766, 8767)

        /** Shared look of the LAN pages: dark, one column, readable on a phone. */
        const val PAGE_STYLE = """
              body{font-family:system-ui,sans-serif;background:#0e0f12;color:#edeff3;max-width:560px;margin:40px auto;padding:0 16px}
              h1{font-size:22px} p,li{color:#a7adb8;line-height:1.5} .card{background:#181a20;border-radius:14px;padding:18px;margin:16px 0}
              button{background:#5eead4;color:#0e0f12;border:0;border-radius:10px;padding:10px 16px;font-weight:600;margin-top:10px}
              textarea,input[type=text]{width:100%;box-sizing:border-box;background:#0e0f12;color:#edeff3;border:1px solid #2a2e37;border-radius:10px;padding:10px}
              textarea{min-height:120px} code{color:#edeff3}
              #status{color:#5eead4;white-space:pre-wrap}
        """

        private val IMPORT_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Slopstream import</title>
            <style>$PAGE_STYLE</style></head><body>
            <h1>Send subscriptions to Slopstream</h1>
            <div class="card"><p>NewPipe: Settings &rarr; Backup and restore &rarr; Export database (zip), or Subscriptions &rarr; Export to file (json). Google Takeout: subscriptions.csv.</p>
            <input type="file" id="file"><br><button onclick="sendFile()">Send file</button></div>
            <div class="card"><p>Or paste channel links, @handles or ids, one per line.</p>
            <textarea id="text" placeholder="https://www.youtube.com/@channel"></textarea><button onclick="sendText()">Send list</button></div>
            <p id="status"></p>
            <script>
              async function send(body){const s=document.getElementById('status');s.textContent='Sending…';
                try{const r=await fetch('/upload',{method:'POST',body});s.textContent=await r.text();}catch(e){s.textContent='Failed: '+e;}}
              function sendFile(){const f=document.getElementById('file').files[0];if(f)send(f);}
              function sendText(){const t=document.getElementById('text').value;if(t.trim())send(new Blob([t]));}
            </script></body></html>
        """.trimIndent()
    }
}
