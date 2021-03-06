/**
 * Copyright 2018 Mario Mueller <mario@xenji.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.github.xenji.homee.api

import com.github.xenji.homee.metrics.updateMetrics
import com.github.xenji.homee.metrics.updateRelationships
import mu.KLogging
import org.http4k.format.Moshi
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import java.lang.Exception
import java.net.URI
import java.time.Duration
import java.util.Timer
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.thread

/**
 * Implementation for the websocket connection to the homee server.
 */
internal class HomeeWsClient(
    uri: URI,
    private val pingInterval: Long,
    private val onlyGroup: Int
) : WebSocketClient(uri, draftWithProtocol, homeeWebsocketHeaders, homeeWebsocketTimeout) {

    companion object : KLogging() {
        private val draftWithProtocol = Draft_6455().apply { knownProtocols.add(Protocol("v2")) }
        private val homeeWebsocketHeaders = mapOf("Sec-WebSocket-Protocol" to "v2", "Sec-WebSocket-Version" to "13")
        private const val homeeWebsocketTimeout = 5_000
    }

    @Volatile
    private lateinit var pingThread: Timer

    /**
     * On open, we start a thread that sends a ping every 5 seconds.
     *
     * This is a daemon thread that should not keep the JVM up when e.g. CTRL+C
     * is pressed.
     */
    override fun onOpen(handshakedata: ServerHandshake) {
        Runtime.getRuntime().addShutdownHook(thread(start = false, isDaemon = false) {
            this.closeConnection(-1, "JVM Shutdown")
        })

        if (::pingThread.isInitialized) {
            pingThread.cancel()
        }

        pingThread =
            fixedRateTimer(name = "ping_timer", daemon = true, period = Duration.ofSeconds(pingInterval).toMillis()) {
                sendPing()
            }
    }

    /**
     * On close, we try to reconnect up to three times.
     *
     * TODO: Find out if any case exists where remote==false and we still want to reconnect!
     */
    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        logger.info { "Closed WS connection: {code: $code, reason: '$reason', remote: $remote}" }
        logger.debug { "Shutting down ping thread" }
        if (::pingThread.isInitialized) {
            pingThread.cancel()
        }
    }

    /**
     * When any message is received, we just want those that start with `{"nodes":[{`.
     *
     * Kotlin has no real pattern matching mechanics, therefore we do the poor-mans `startsWith`.
     *
     * This is all pretty wonky and needs a more sophisticated approach later.
     */
    override fun onMessage(message: String?) {
        logger.trace { "Received $message" }
        if (message != null) {
            if (message.startsWith("""{"nodes":[{""")) {
                val nr = Moshi.asA<NodesResponse>(message)
                updateMetrics(nr.nodes, onlyGroup)
            }
            if (message.startsWith("""{"relationships":""")) {
                val relationships = Moshi.asA<Relationships>(message)
                updateRelationships(relationships.relationships)
            }
        }
    }

    /**
     * On error, we just rethrow for now.
     */
    override fun onError(ex: Exception) = throw ex
}
