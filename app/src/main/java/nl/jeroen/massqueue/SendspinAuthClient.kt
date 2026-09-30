package nl.jeroen.massqueue

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * OkHttp-client voor sendspin-jvm die via MA's geauthenticeerde proxy verbindt
 * (`wss://<server>/sendspin`, bijv. over Tailscale).
 *
 * Die proxy eist als eerste bericht `{"type":"auth","token":…,"client_id":…}` en antwoordt
 * met `{"type":"auth_ok"}`; daarna is het gewoon Sendspin. De bibliotheek kent die stap niet,
 * dus we houden `onOpen` tegen tot de auth gelukt is. Pas dan stuurt de bibliotheek zijn
 * client/hello. Zonder token (bijv. de directe poort 8927) gaat alles ongewijzigd door.
 *
 * sendspin-jvm gebruikt van de client alleen [newWebSocket].
 */
class SendspinAuthClient(
    private val delegate: OkHttpClient,
    private val tokenForUrl: (String) -> String?,
    private val clientId: String
) : OkHttpClient() {

    /** Reden waarom de proxy de laatste verbinding vóór auth_ok sloot (bijv. "Invalid or expired token"). */
    @Volatile var lastRejection: String? = null
        private set

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        // OkHttp schrijft ws(s):// intern om naar http(s)://; terugzetten zodat het adres
        // vergelijkbaar is met wat aan SendSpinClient.connect() is meegegeven
        val wsUrl = request.url.toString().replaceFirst(Regex("^http"), "ws")
        val token = tokenForUrl(wsUrl)
            ?: return delegate.newWebSocket(request, listener)
        return delegate.newWebSocket(request, AuthListener(listener, token))
    }

    private inner class AuthListener(
        private val inner: WebSocketListener,
        private val token: String
    ) : WebSocketListener() {

        @Volatile private var authed = false
        private var openResponse: Response? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openResponse = response
            val auth = JSONObject()
                .put("type", "auth")
                .put("token", token)
                // Laat MA deze speler meteen aan de gebruiker van het token koppelen
                .put("client_id", clientId)
            webSocket.send(auth.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (authed) {
                inner.onMessage(webSocket, text)
                return
            }
            val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
            if (type == "auth_ok") {
                authed = true
                lastRejection = null
                inner.onOpen(webSocket, openResponse ?: return)
            } else {
                webSocket.close(4001, "Unexpected auth reply")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (authed) inner.onMessage(webSocket, bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!authed) lastRejection = reason.ifBlank { "code $code" }
            inner.onClosing(webSocket, code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            inner.onClosed(webSocket, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            inner.onFailure(webSocket, t, response)
    }
}
