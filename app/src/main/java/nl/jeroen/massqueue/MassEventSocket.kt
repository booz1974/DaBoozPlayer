package nl.jeroen.massqueue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Eén server-push van Music Assistant. */
data class MassEvent(val type: String, val objectId: String?)

/**
 * Houdt een WebSocket open naar de Music Assistant server (`<base>/ws`) en
 * geeft de binnenkomende events door. Vervangt effectief het snelle pollen:
 * de ViewModel ververst nu op een event i.p.v. elke 2,5 s.
 *
 * De socket praat NIET terug met commando's; die blijven via HTTP lopen.
 * Bij verbindingsverlies wordt met oplopende backoff opnieuw verbonden.
 */
class MassEventSocket {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: geen read-timeout
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var baseUrl: String = ""
    private var authToken: String? = null

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    private var running = false

    private val _events = MutableSharedFlow<MassEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<MassEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun updateConfig(newBaseUrl: String, newToken: String?) {
        baseUrl = newBaseUrl
        authToken = newToken
        if (running) {
            // Forceer een verse verbinding met de nieuwe config.
            attempts = 0
            closeCurrent()
            connect()
        }
    }

    fun start() {
        if (running) return
        running = true
        attempts = 0
        connect()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        closeCurrent()
        _connected.value = false
    }

    // ---- intern ------------------------------------------------------------

    private fun closeCurrent() {
        runCatching { ws?.close(1000, null) }
        ws = null
    }

    private fun wsUrl(): String {
        val u = baseUrl.trim().trimEnd('/')
        val scheme = when {
            u.startsWith("https://", ignoreCase = true) -> "wss://" + u.substring(8)
            u.startsWith("http://", ignoreCase = true) -> "ws://" + u.substring(7)
            u.startsWith("wss://", ignoreCase = true) || u.startsWith("ws://", ignoreCase = true) -> u
            else -> "wss://$u"
        }
        return "$scheme/ws"
    }

    private fun connect() {
        if (!running || baseUrl.isBlank()) return
        closeCurrent()

        // Auth gaat via een `auth`-commando na connect (niet via header/queryparam).
        val request = Request.Builder().url(wsUrl()).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                // 'connected' pas na succesvolle auth (dan stromen de events).
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                android.util.Log.w("MassWS", "verbinding mislukt: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return

        // Server-info bij connect (geen 'event', wel versievelden): authenticeren.
        if (json.has("server_version") || json.has("schema_version")) {
            if (!authToken.isNullOrBlank()) {
                ws?.send("{\"command\":\"auth\",\"message_id\":\"auth\",\"args\":{\"token\":\"$authToken\"}}")
            }
            return
        }

        // Auth-antwoord: daarna één commando sturen; MA begint pas events te
        // pushen zodra de sessie een commando heeft verwerkt.
        if (json.optString("message_id") == "auth") {
            val ok = json.optJSONObject("result")?.optBoolean("authenticated") == true
            if (ok) {
                _connected.value = true
                ws?.send("{\"command\":\"players/all\",\"message_id\":\"kick\",\"args\":{}}")
            } else {
                android.util.Log.w("MassWS", "auth geweigerd")
            }
            return
        }

        // Commando-antwoorden (message_id maar geen event) negeren.
        val eventType = json.optString("event").takeIf { it.isNotBlank() } ?: return

        _events.tryEmit(
            MassEvent(
                type = eventType,
                objectId = json.optString("object_id").takeIf { it.isNotBlank() }
            )
        )
    }

    private fun scheduleReconnect() {
        if (!running) return
        reconnectJob?.cancel()
        val backoff = minOf(30_000L, 2_000L * (1L shl minOf(attempts, 4)))
        attempts++
        reconnectJob = scope.launch {
            delay(backoff)
            if (running) connect()
        }
    }
}
