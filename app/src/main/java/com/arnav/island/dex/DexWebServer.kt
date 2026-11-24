package com.arnav.island.dex

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight zero-dependency HTTP + RFC 6455 WebSocket server embedded inside BreezeIsland.
 *
 * Lets any Windows / Mac / Linux computer view and control the Galaxy Z Flip 5's Samsung DeX
 * desktop in Chrome or Edge at 60fps via WebCodecs H.264 (Annex-B) with real PC mouse & keyboard —
 * over Wi-Fi (`http://<phone-ip>:8765`) OR USB (`adb forward tcp:8765 tcp:8765` -> `http://localhost:8765`).
 */
class DexWebServer(
    private val controllerProvider: () -> DexInputController?,
    private val videoInfoProvider: () -> Triple<Int, Int, String>,
    private val cachedInitProvider: () -> Pair<ByteArray?, ByteArray?>,
) {
    companion object {
        private const val TAG = "DexWebServer"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val DEFAULT_PORT = 8765

        internal fun computeWebSocketAccept(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((clientKey.trim() + WS_GUID).toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(sha1, Base64.NO_WRAP)
        }

        fun findLocalIpv4Address(): String? = runCatching {
            val candidates = ArrayList<Pair<Int, String>>()
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("rndis") || name.startsWith("usb") -> 1
                    name.startsWith("eth") -> 2
                    name.startsWith("ap") || name.startsWith("swlan") -> 3
                    else -> 4
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) {
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        }.getOrNull()
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray, val droppable: Boolean)

    private inner class ClientConnection(private val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        private val sendQueue = LinkedBlockingQueue<WsFrame>(36)
        private val alive = AtomicBoolean(true)
        private var sentInitialKeyframe = false

        fun start(input: BufferedInputStream) {
            clients.add(this)
            _clientCount.value = clients.size

            // Immediately announce video geometry & codec and push cached SPS/PPS + keyframe.
            val (w, h, codec) = videoInfoProvider()
            if (w > 0 && h > 0) {
                sendMeta(w, h, codec)
                val (cfg, kf) = cachedInitProvider()
                if (cfg != null && kf != null) {
                    sendVideoPacket(isKeyFrame = true, ptsUs = 0L, data = concat(cfg, kf))
                    sentInitialKeyframe = true
                }
            }

            val writer = Thread({
                try {
                    while (alive.get() && running.get()) {
                        val frame = sendQueue.take()
                        writeWsFrame(out, frame.opcode, frame.payload)
                    }
                } catch (_: Throwable) {
                } finally {
                    close()
                }
            }, "breeze-dex-ws-out").apply { start() }

            try {
                readLoop(DataInputStream(input))
            } catch (_: Throwable) {
            } finally {
                close()
                writer.interrupt()
            }
        }

        fun sendMeta(width: Int, height: Int, codec: String) {
            val json = JSONObject()
                .put("type", "meta")
                .put("width", width)
                .put("height", height)
                .put("codec", codec)
                .toString()
            enqueue(WsFrame(opcode = 0x1, payload = json.toByteArray(Charsets.UTF_8), droppable = false))
        }

        fun sendVideoPacket(isKeyFrame: Boolean, ptsUs: Long, data: ByteArray) {
            if (!isKeyFrame && !sentInitialKeyframe) return
            if (isKeyFrame) sentInitialKeyframe = true

            val buf = ByteBuffer.allocate(9 + data.size)
            buf.put(if (isKeyFrame) 1.toByte() else 0.toByte())
            buf.putLong(ptsUs)
            buf.put(data)
            enqueue(WsFrame(opcode = 0x2, payload = buf.array(), droppable = !isKeyFrame))
        }

        private fun enqueue(frame: WsFrame) {
            if (!alive.get()) return
            if (!sendQueue.offer(frame)) {
                if (frame.droppable) return
                // Make room for keyframe / meta by dropping queued delta frames.
                while (sendQueue.remainingCapacity() == 0) {
                    val removed = sendQueue.poll() ?: break
                    if (!removed.droppable) {
                        sendQueue.offer(removed)
                        break
                    }
                }
                sendQueue.offer(frame)
            }
        }

        private fun readLoop(dis: DataInputStream) {
            while (alive.get() && running.get()) {
                val b0 = dis.read()
                if (b0 == -1) break
                val b1 = dis.read()
                if (b1 == -1) break

                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = dis.readUnsignedShort().toLong()
                } else if (len == 127L) {
                    len = dis.readLong()
                }
                if (len < 0 || len > 256 * 1024) break

                val mask = if (masked) ByteArray(4).also { dis.readFully(it) } else null
                val payload = ByteArray(len.toInt())
                dis.readFully(payload)
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x8 -> break // Close
                    0x9 -> enqueue(WsFrame(opcode = 0xA, payload = payload, droppable = false)) // Pong
                    0x1 -> handleClientMessage(String(payload, Charsets.UTF_8))
                }
            }
        }

        fun close() {
            if (!alive.compareAndSet(true, false)) return
            clients.remove(this)
            _clientCount.value = clients.size
            runCatching { socket.close() }
        }
    }

    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    @Volatile
    private var lastConfig: ByteArray? = null

    fun start(): Int? {
        if (!running.compareAndSet(false, true)) return port
        val srv = bindPort() ?: run {
            running.set(false)
            return null
        }
        serverSocket = srv
        port = srv.localPort
        val ip = findLocalIpv4Address() ?: "127.0.0.1"
        _url.value = "http://$ip:$port"

        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    Thread({ handleSocket(client) }, "breeze-dex-http").start()
                } catch (_: IOException) {
                    break
                }
            }
        }, "breeze-dex-web-accept").apply { start() }

        Log.i(TAG, "DeX for PC web server listening on ${_url.value}")
        return port
    }

    fun stop() {
        if (!running.compareAndSet(false, true)) {
            running.set(false)
        }
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        clients.forEach { it.close() }
        clients.clear()
        _clientCount.value = 0
        _url.value = null
    }

    fun onVideoSizeChanged(width: Int, height: Int, codec: String) {
        for (c in clients) c.sendMeta(width, height, codec)
    }

    fun onEncodedPacket(isConfig: Boolean, isKeyFrame: Boolean, ptsUs: Long, payload: ByteArray) {
        if (isConfig) {
            lastConfig = payload
            val (w, h, codec) = videoInfoProvider()
            if (w > 0 && h > 0) onVideoSizeChanged(w, h, codec)
            return
        }
        if (clients.isEmpty()) return
        val bytesToSend = if (isKeyFrame) {
            val cfg = lastConfig ?: cachedInitProvider().first
            if (cfg != null) concat(cfg, payload) else payload
        } else {
            payload
        }
        for (c in clients) {
            c.sendVideoPacket(isKeyFrame, ptsUs, bytesToSend)
        }
    }

    private fun bindPort(): ServerSocket? {
        for (candidate in DEFAULT_PORT..(DEFAULT_PORT + 10)) {
            try {
                return ServerSocket(candidate, 8, InetAddress.getByName("0.0.0.0"))
            } catch (_: IOException) {
            }
        }
        return runCatching { ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")) }.getOrNull()
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readHttpHeaders(input) ?: run {
                socket.close()
                return
            }
            val requestLine = headers.firstOrNull().orEmpty()
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val wsKey = headers.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()

            if (path.startsWith("/ws") && !wsKey.isNullOrBlank()) {
                val accept = computeWebSocketAccept(wsKey)
                val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                socket.getOutputStream().apply {
                    write(handshake.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                ClientConnection(socket).start(input)
                return
            }

            val htmlBytes = WEB_CLIENT_HTML.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${htmlBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(response.toByteArray(Charsets.US_ASCII))
                write(htmlBytes)
                flush()
            }
            socket.close()
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): List<String>? {
        val out = ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < 16_384) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else 0
                else -> 0
            }
            if (state == 4) break
        }
        return out.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
    }

    private fun writeWsFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(out) {
            out.write(0x80 or (opcode and 0x0F))
            val len = payload.size
            when {
                len < 126 -> out.write(len)
                len <= 0xFFFF -> {
                    out.write(126)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                }
                else -> {
                    out.write(127)
                    val len64 = len.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        out.write(((len64 ushr shift) and 0xFFL).toInt())
                    }
                }
            }
            out.write(payload)
            out.flush()
        }
    }

    private fun handleClientMessage(rawJson: String) {
        val ctrl = controllerProvider() ?: return
        val (vw, vh, _) = videoInfoProvider()
        runCatching {
            val o = JSONObject(rawJson)
            when (o.optString("t")) {
                "move" -> {
                    val x = o.optInt("x")
                    val y = o.optInt("y")
                    val b = o.optInt("b", 0)
                    if (b == 0) ctrl.sendHoverMove(x, y, vw, vh)
                    else ctrl.sendMouseDrag(x, y, vw, vh, b)
                }
                "down" -> {
                    ctrl.sendMouseDown(
                        o.optInt("x"),
                        o.optInt("y"),
                        vw,
                        vh,
                        o.optInt("btn", DexInputController.BUTTON_PRIMARY),
                        o.optInt("b", DexInputController.BUTTON_PRIMARY),
                    )
                }
                "up" -> {
                    ctrl.sendMouseUp(
                        o.optInt("x"),
                        o.optInt("y"),
                        vw,
                        vh,
                        o.optInt("btn", DexInputController.BUTTON_PRIMARY),
                        o.optInt("b", 0),
                    )
                }
                "wheel" -> {
                    ctrl.sendScrollAt(
                        o.optInt("x"),
                        o.optInt("y"),
                        vw,
                        vh,
                        o.optDouble("dx", 0.0).toFloat(),
                        o.optDouble("dy", 0.0).toFloat(),
                    )
                }
                "key" -> {
                    ctrl.sendKeyPress(o.optInt("code"), o.optInt("meta", 0))
                }
                "text" -> {
                    ctrl.sendText(o.optString("s"))
                }
            }
        }
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }
}

private const val WEB_CLIENT_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>BreezeIsland · Samsung DeX for PC</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; background: #05070b; color: #e2e8f0; font-family: system-ui, -apple-system, Segoe UI, sans-serif; overflow: hidden; user-select: none; }
  #stage { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; }
  canvas { max-width: 100vw; max-height: 100vh; background: #000; outline: none; cursor: default; }
  #bar { position: fixed; top: 10px; left: 50%; transform: translateX(-50%); display: flex; gap: 8px; align-items: center; padding: 7px 14px; border-radius: 999px; background: rgba(15, 23, 42, 0.82); backdrop-filter: blur(12px); border: 1px solid rgba(255,255,255,0.14); opacity: 0.25; transition: opacity .2s; z-index: 10; }
  #bar:hover, #bar.visible { opacity: 1; }
  button { background: rgba(255,255,255,0.1); color: #fff; border: 0; border-radius: 999px; padding: 6px 12px; font-size: 12px; font-weight: 600; cursor: pointer; }
  button:hover { background: rgba(56,189,248,0.35); }
  #status { font-size: 12px; color: #94a3b8; margin-right: 4px; }
  #overlay { position: fixed; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; background: rgba(5,7,11,0.88); z-index: 5; text-align: center; padding: 24px; }
  #overlay.hidden { display: none; }
</style>
</head>
<body>
<div id="bar" class="visible">
  <span id="status">Connecting to BreezeIsland DeX…</span>
  <button onclick="sendKey(4)">Back</button>
  <button onclick="sendKey(3)">Home</button>
  <button onclick="sendKey(187)">Recents</button>
  <button onclick="toggleFs()">Fullscreen</button>
</div>
<div id="overlay">
  <h2 style="font-weight:600;color:#38bdf8">BreezeIsland · Samsung DeX for PC</h2>
  <p id="msg" style="color:#cbd5e1;max-width:460px;font-size:14px">Waiting for video stream from your Galaxy Z Flip 5…</p>
</div>
<div id="stage"><canvas id="c" tabindex="0"></canvas></div>
<script>
const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const overlay = document.getElementById('overlay');
const msgEl = document.getElementById('msg');
const bar = document.getElementById('bar');

let vw = 1920, vh = 1080, codecStr = 'avc1.64002a';
let decoder = null, configured = false, awaitingKey = true;
let ws = null;

function setMsg(text, hideOverlay = false) {
  statusEl.textContent = text;
  msgEl.textContent = text;
  overlay.classList.toggle('hidden', hideOverlay);
}

function initDecoder() {
  if (!('VideoDecoder' in window)) {
    setMsg('Your browser does not support WebCodecs H.264. Please open this URL in Chrome or Edge.', false);
    return;
  }
  if (decoder) { try { decoder.close(); } catch (_) {} }
  configured = false;
  awaitingKey = true;
  decoder = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      frame.close();
      if (!overlay.classList.contains('hidden')) {
        setMsg('DeX ' + vw + '×' + vh, true);
        setTimeout(() => bar.classList.remove('visible'), 2500);
      }
    },
    error: (e) => {
      console.error('VideoDecoder error', e);
      configured = false;
      awaitingKey = true;
      setTimeout(initDecoder, 300);
    }
  });
  decoder.configure({
    codec: codecStr,
    avc: { format: 'annexb' },
    optimizeForLatency: true,
    hardwareAcceleration: 'prefer-hardware'
  });
  configured = true;
}

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws');
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => setMsg('Connected · waiting for first frame…', false);
  ws.onclose = () => {
    setMsg('Disconnected · reconnecting…', false);
    setTimeout(connect, 1500);
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      const m = JSON.parse(ev.data);
      if (m.type === 'meta') {
        vw = m.width || vw;
        vh = m.height || vh;
        codecStr = m.codec || codecStr;
        canvas.width = vw;
        canvas.height = vh;
        initDecoder();
      }
      return;
    }
    if (!configured || !decoder || decoder.state !== 'configured') return;
    const view = new DataView(ev.data);
    const isKey = (view.getUint8(0) & 1) !== 0;
    const pts = Number(view.getBigInt64(1));
    const payload = new Uint8Array(ev.data, 9);
    if (awaitingKey) {
      if (!isKey) return;
      awaitingKey = false;
    }
    try {
      decoder.decode(new EncodedVideoChunk({
        type: isKey ? 'key' : 'delta',
        timestamp: pts,
        data: payload
      }));
    } catch (e) {
      awaitingKey = true;
    }
  };
}

function sendJson(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}
function sendKey(code, meta = 0) { sendJson({ t: 'key', code, meta }); }
function toggleFs() {
  if (!document.fullscreenElement) document.documentElement.requestFullscreen?.();
  else document.exitFullscreen?.();
}

function toVideoCoords(e) {
  const r = canvas.getBoundingClientRect();
  const x = Math.max(0, Math.min(vw - 1, Math.round((e.clientX - r.left) * vw / r.width)));
  const y = Math.max(0, Math.min(vh - 1, Math.round((e.clientY - r.top) * vh / r.height)));
  return { x, y };
}
function toAndroidButtons(buttons) {
  let mask = 0;
  if (buttons & 1) mask |= 1;
  if (buttons & 2) mask |= 2;
  if (buttons & 4) mask |= 4;
  return mask;
}
function toAndroidButton(button) {
  if (button === 2) return 2;
  if (button === 1) return 4;
  return 1;
}

canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('mousedown', (e) => {
  e.preventDefault();
  canvas.focus();
  const { x, y } = toVideoCoords(e);
  sendJson({ t: 'down', x, y, btn: toAndroidButton(e.button), b: toAndroidButtons(e.buttons) });
});
canvas.addEventListener('mousemove', (e) => {
  const { x, y } = toVideoCoords(e);
  sendJson({ t: 'move', x, y, b: toAndroidButtons(e.buttons) });
});
window.addEventListener('mouseup', (e) => {
  const { x, y } = toVideoCoords(e);
  sendJson({ t: 'up', x, y, btn: toAndroidButton(e.button), b: toAndroidButtons(e.buttons) });
});
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  const { x, y } = toVideoCoords(e);
  sendJson({ t: 'wheel', x, y, dx: -e.deltaX / 100, dy: -e.deltaY / 100 });
}, { passive: false });

const KEY_MAP = {
  'Enter': 66, 'Backspace': 67, 'Delete': 112, 'Escape': 111, 'Tab': 61,
  'ArrowUp': 19, 'ArrowDown': 20, 'ArrowLeft': 21, 'ArrowRight': 22,
  'Home': 122, 'End': 123, 'PageUp': 92, 'PageDown': 93
};
window.addEventListener('keydown', (e) => {
  if (e.key === 'F11') return;
  if (e.ctrlKey && e.key.length === 1) {
    const ch = e.key.toLowerCase();
    const aCode = ch.charCodeAt(0) - 97 + 29;
    if (aCode >= 29 && aCode <= 54) {
      e.preventDefault();
      sendKey(aCode, 0x1000 | 0x2000); // META_CTRL_ON | META_CTRL_LEFT_ON
      return;
    }
  }
  if (KEY_MAP[e.key]) {
    e.preventDefault();
    sendKey(KEY_MAP[e.key]);
    return;
  }
  if (!e.ctrlKey && !e.metaKey && !e.altKey && e.key.length === 1) {
    e.preventDefault();
    sendJson({ t: 'text', s: e.key });
  }
});

connect();
</script>
</body>
</html>
"""
