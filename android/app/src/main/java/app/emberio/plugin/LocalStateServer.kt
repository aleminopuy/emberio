package app.emberio.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Serves the mug's state to the Emberio watchface plugin, which runs inside the Pebble app on
 * this phone. Bound to 127.0.0.1 only, so nothing off the phone can reach it, and read-only:
 * it can't change the mug. (Plugins can't talk to Android apps directly yet; reaching
 * localhost is what the plugin's LocalNetwork permission is for.)
 */
class LocalStateServer(
    private val port: Int = PORT,
    private val body: () -> String,
) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val socket = try {
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.w(TAG, "Can't listen on 127.0.0.1:$port: ${e.message}")
            return
        }
        server = socket
        thread(name = "emberio-plugin-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                    continue
                }
                try {
                    handle(client)
                } catch (e: Exception) {
                    Log.w(TAG, "request failed: ${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 2000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        // Drain the headers; we don't need any of them.
        var headerBytes = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > 16 * 1024) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1)?.substringBefore('?')

        val (status, payload) = when {
            method != "GET" && method != "HEAD" -> "405 Method Not Allowed" to """{"error":"read-only"}"""
            path == "/v1/state" -> "200 OK" to body()
            path == "/" -> "200 OK" to """{"app":"emberio","endpoints":["/v1/state"]}"""
            else -> "404 Not Found" to """{"error":"not found"}"""
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") out.write(bytes)
        out.flush()
    }

    companion object {
        const val PORT = 47811
        private const val TAG = "LocalStateServer"
    }
}
