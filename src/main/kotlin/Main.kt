package com.voice

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun createId(length: Int = 10) : String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { charset.random() }
        .joinToString("")
}

enum class MessageType {
    CreateChannel,
    ChannelCreated,
    ClientAdded,
    NewClient,
    NewOffer,
    NewAnswer,
    IceCandidate,
}

@Serializable
data class Client(
    val id: String,
    val session: DefaultWebSocketSession,
    var offer: String? = null,
    var answer: String? = null,
    var candidates: MutableList<ICECandidate> = mutableListOf()
)

@Serializable
data class ICECandidate(
    val candidate: String,
    val sdpMLineIndex: Int,
    val sdpMid: String,
    val address: String? = null,
    val component: String? = null,
    val foundation: String? = null,
    val port: Int? = null,
    val priority: Int? = null,
    val protocol: String? = null,
    val relatedAddress: String? = null,
    val relatedPort: Int? = null,
    val tcpType: String? = null,
    val type: String? = null,
    val usernameFragment: String? = null,
)

@Serializable
data class MessagePayload(
    val id: String? = null,
    val clientId: String? = null,
    val offer: String? = null,
    val answer: String? = null,
    val iceCandidates: List<ICECandidate>? = null,
)

@Serializable
data class Message(
    val type: MessageType,
    val payload: MessagePayload? = null,
)

class VoiceChannel {
    val clients: MutableMap<String, Client> = mutableMapOf()
    var session: DefaultWebSocketSession? = null;
}

val Clients = mutableMapOf<String, VoiceChannel>()

fun main(args: Array<String>) {
    embeddedServer(Netty, 9000) {
        install(WebSockets)
        install(ContentNegotiation) {
            json()
        }

        routing {
            webSocket("/ws") {
                for(frame in incoming) {
                    frame as? Frame.Text ?: continue
                    val data = Json.decodeFromString<Message>(frame.readText())

                    when(data.type) {
                        MessageType.CreateChannel -> {
                            val id = createId();
                            val channel = VoiceChannel()
                            Clients[id] = channel;
                            channel.session = this

                            send(Json.encodeToString(Message(
                                MessageType.ChannelCreated,
                                MessagePayload(
                                    id
                                ),
                            )))
                        }
                        MessageType.NewClient -> {
                            val clientId = createId();
                            Clients[data.payload?.id]?.clients?.set(clientId, Client(
                                clientId,
                                this,
                            ));
                            Clients[data.payload?.id]?.session?.send(Json.encodeToString(Message(
                                MessageType.ClientAdded,
                                MessagePayload(
                                    data.payload?.id,
                                    clientId
                                )
                            )))

                            send(Json.encodeToString(Message(
                                MessageType.ClientAdded,
                                MessagePayload(
                                    data.payload?.id,
                                    clientId
                                )
                            )))
                        }
                        MessageType.NewOffer -> {
                            val channel = Clients[data.payload?.id]
                            val client = channel?.clients?.get(data.payload?.clientId)
                            client?.offer = data.payload?.offer;
                            client?.session?.send(Json.encodeToString(
                                Message(
                                    MessageType.NewOffer,
                                    MessagePayload(
                                        data.payload?.id,
                                        data.payload?.clientId,
                                        data.payload?.offer,
                                    )
                                )
                            ))
                        }
                        MessageType.NewAnswer -> {
                            val channel = Clients[data.payload?.id]
                            val client = channel?.clients?.get(data.payload?.clientId)
                            client?.answer = data.payload?.answer
                            channel?.session?.send(Json.encodeToString(
                                Message(
                                    MessageType.NewAnswer,
                                    MessagePayload(
                                        data.payload?.id,
                                        data.payload?.clientId,
                                        null,
                                        data.payload?.answer,
                                    )
                                )
                            ))
                        }
                        MessageType.IceCandidate -> {
                            val channel = Clients[data.payload?.id]
                            val client = channel?.clients?.get(data.payload?.clientId)
                            data.payload?.iceCandidates?.forEach { candidate ->
                                if (client == null) {
                                    channel?.session?.send(Json.encodeToString(
                                        Message(
                                            MessageType.IceCandidate,
                                            MessagePayload(
                                                data.payload.id,
                                                null,
                                                null,
                                                null,
                                                listOf(candidate),
                                            )
                                        )
                                    ))
                                } else {
                                    client.candidates.add(candidate)

                                    client.session.send(Json.encodeToString(
                                        Message(
                                            MessageType.IceCandidate,
                                            MessagePayload(
                                                data.payload.id,
                                                data.payload.clientId,
                                                null,
                                                null,
                                                client.candidates,
                                            )
                                        )
                                    ))
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }.start(wait = true)
}