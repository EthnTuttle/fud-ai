package com.apoorvdarshan.calorietracker.services.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Base64

// The live session's wire protocols, modelled on Scowld's LiveWire.swift (same author,
// MIT). Each provider frames setup, audio, text, images and tool calls differently; the
// rest of the assistant only sees [LiveEvent]s.

data class LiveSessionConfig(
    val provider: LiveProvider,
    val model: String,
    val voice: String,
    val instructions: String,
    val tools: List<VoiceToolSpec> = emptyList(),
    /** Own-key providers. */
    val apiKey: String? = null,
    /** Hosted: the RevenueCat anonymous app user id. */
    val hostedUserId: String? = null,
    val hostedUrl: String = HostedLiveWire.DEFAULT_URL
)

/** A function call from the model. [arguments] is always an object (empty when missing or malformed). */
data class LiveToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    /** OpenAI/xAI: the response that asked for it, so the next response starts after all outputs. */
    val responseId: String? = null
)

data class LiveToolResult(val callId: String, val name: String, val result: JsonObject)

/** Why a connection ended unexpectedly. */
sealed interface LiveFailure {
    /** The WebSocket upgrade was refused; hosted sends a JSON `error` code. */
    data class Refused(val status: Int, val code: String?) : LiveFailure
    /** The server closed the socket. */
    data class Closed(val code: Int, val reason: String?) : LiveFailure
    data class Network(val message: String?) : LiveFailure
}

/** What a provider says during a live session, in one shared vocabulary. */
sealed interface LiveEvent {
    /** Configured; audio and text are accepted. */
    data object Ready : LiveEvent

    /** Part of the reply: 16-bit little-endian mono PCM at the wire's output rate. */
    class Audio(val pcm: ByteArray, val itemId: String?) : LiveEvent

    /** Part of the transcript of what the assistant is saying. */
    data class AssistantText(val text: String) : LiveEvent

    /** What the user said: a part to append, or the whole utterance so far. */
    data class UserText(val text: String, val replacing: Boolean) : LiveEvent

    data object UserSpeechStarted : LiveEvent
    data object UserSpeechStopped : LiveEvent

    /** Gemini stopped its reply because the user spoke. */
    data object Interrupted : LiveEvent

    /** OpenAI/xAI started a response. */
    data class ResponseStarted(val responseId: String?) : LiveEvent

    /** The turn ended; Realtime can end an incomplete or cancelled response too. */
    data class TurnComplete(val responseId: String? = null, val successful: Boolean = true) : LiveEvent

    data class ToolCalls(val calls: List<LiveToolCall>) : LiveEvent
    data class ToolCallsCancelled(val ids: List<String>) : LiveEvent

    /** Gemini: a handle that resumes this session on a new connection. */
    data class ResumptionHandle(val handle: String) : LiveEvent

    /** Gemini: this connection closes soon; move to a new one. */
    data object GoAway : LiveEvent

    /** Hosted: voice time left (allowance plus credits). */
    data class HostedStatus(val secondsRemaining: Int) : LiveEvent

    /** Hosted: `voice_quota_exceeded` or `session_limit`, sent right before the close. */
    data class HostedNotice(val code: String) : LiveEvent

    /** The provider reported a problem; the session continues unless it also closes. */
    data class ProviderError(val message: String) : LiveEvent

    /** The connection ended. A null failure is a normal close the app asked for. */
    data class Closed(val failure: LiveFailure?) : LiveEvent
}

/** How one provider frames a live session on the wire. */
interface LiveWire {
    val provider: LiveProvider
    val inputSampleRate: Int get() = provider.inputSampleRate
    val outputSampleRate: Int get() = provider.outputSampleRate

    /** Ready only after the provider acknowledges the setup (Gemini's setupComplete). */
    val waitsForSetupAcknowledgement: Boolean

    /** Tool outputs need an explicit `response.create` before the model continues. */
    val toolOutputsNeedResponseCreate: Boolean

    fun request(config: LiveSessionConfig): Request
    fun setupMessages(config: LiveSessionConfig, resumptionHandle: String?): List<String>
    fun audioMessage(pcm16: ByteArray): String

    /** A user text turn (card confirmations); [respond] asks the model to answer it. */
    fun textMessages(text: String, respond: Boolean): List<String>

    /** A camera frame on its own. Empty when the provider takes no images. */
    fun imageMessages(jpeg: ByteArray): List<String>
    fun toolResultMessages(results: List<LiveToolResult>): List<String>
    fun responseCreateMessage(): String?

    /** Tells the provider how much of reply [itemId] the user heard, or null if unsupported. */
    fun truncateMessage(itemId: String, audioEndMs: Int): String?

    /** Server messages arrive as text or binary frames; both are UTF-8 JSON. */
    fun parse(message: String): List<LiveEvent>

    companion object {
        fun forConfig(config: LiveSessionConfig): LiveWire = when (config.provider) {
            LiveProvider.HOSTED -> HostedLiveWire()
            LiveProvider.GEMINI -> GeminiLiveWire()
            LiveProvider.OPENAI -> OpenAIRealtimeWire()
            LiveProvider.XAI -> XAIVoiceWire()
        }
    }
}

// MARK: - Gemini Live

/** Gemini Live API over a raw WebSocket (BidiGenerateContent). */
open class GeminiLiveWire : LiveWire {
    override val provider: LiveProvider = LiveProvider.GEMINI
    override val waitsForSetupAcknowledgement = true
    override val toolOutputsNeedResponseCreate = false

    override fun request(config: LiveSessionConfig): Request = Request.Builder()
        .url(ENDPOINT)
        // The key goes in a header, never the URL, so it cannot end up in logged URLs.
        .header("x-goog-api-key", config.apiKey.orEmpty())
        .build()

    override fun setupMessages(config: LiveSessionConfig, resumptionHandle: String?): List<String> {
        val model = if (config.model.startsWith("models/")) config.model else "models/${config.model}"
        val setup = buildJsonObject {
            put("model", model)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", config.instructions) }) }
            }
            if (config.tools.isNotEmpty()) put("tools", VoiceToolCatalog.geminiTools(config.tools))
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            // Without compression an audio session ends after 15 minutes, and one with
            // camera frames after 2.
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            putJsonObject("sessionResumption") { resumptionHandle?.let { put("handle", it) } }
        }
        return listOf(buildJsonObject { put("setup", setup) }.toString())
    }

    override fun audioMessage(pcm16: ByteArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", base64(pcm16))
                put("mimeType", "audio/pcm;rate=$inputSampleRate")
            }
        }
    }.toString()

    override fun textMessages(text: String, respond: Boolean): List<String> = listOf(
        buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
                put("turnComplete", respond)
            }
        }.toString()
    )

    override fun imageMessages(jpeg: ByteArray): List<String> = listOf(
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("video") {
                    put("data", base64(jpeg))
                    put("mimeType", "image/jpeg")
                }
            }
        }.toString()
    )

    override fun toolResultMessages(results: List<LiveToolResult>): List<String> {
        if (results.isEmpty()) return emptyList()
        return listOf(
            buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("id", result.callId)
                                put("name", result.name)
                                put("response", result.result)
                            })
                        }
                    }
                }
            }.toString()
        )
    }

    override fun responseCreateMessage(): String? = null

    override fun truncateMessage(itemId: String, audioEndMs: Int): String? = null

    override fun parse(message: String): List<LiveEvent> {
        val json = LiveJson.objectOrNull(message) ?: return emptyList()
        val events = mutableListOf<LiveEvent>()
        if (json.containsKey("setupComplete")) events += LiveEvent.Ready
        (json["serverContent"] as? JsonObject)?.let { content ->
            val parts = ((content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            for (part in parts) {
                val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
                if (LiveJson.string(inline, "mimeType")?.startsWith("audio/") != true) continue
                val data = LiveJson.string(inline, "data") ?: continue
                val pcm = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: continue
                events += LiveEvent.Audio(pcm, itemId = null)
            }
            LiveJson.string(content["inputTranscription"] as? JsonObject, "text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += LiveEvent.UserText(it, replacing = false) }
            LiveJson.string(content["outputTranscription"] as? JsonObject, "text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += LiveEvent.AssistantText(it) }
            if (LiveJson.bool(content, "interrupted") == true) events += LiveEvent.Interrupted
            if (LiveJson.bool(content, "turnComplete") == true) events += LiveEvent.TurnComplete()
        }
        (json["toolCall"] as? JsonObject)?.let { toolCall ->
            val calls = (toolCall["functionCalls"] as? JsonArray).orEmpty().mapNotNull { element ->
                val call = element as? JsonObject ?: return@mapNotNull null
                val name = LiveJson.string(call, "name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LiveToolCall(
                    id = LiveJson.string(call, "id").orEmpty(),
                    name = name,
                    arguments = call["args"] as? JsonObject ?: JsonObject(emptyMap())
                )
            }
            if (calls.isNotEmpty()) events += LiveEvent.ToolCalls(calls)
        }
        (json["toolCallCancellation"] as? JsonObject)?.let { cancellation ->
            val ids = (cancellation["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (ids.isNotEmpty()) events += LiveEvent.ToolCallsCancelled(ids)
        }
        (json["sessionResumptionUpdate"] as? JsonObject)?.let { update ->
            val handle = LiveJson.string(update, "newHandle")
            if (LiveJson.bool(update, "resumable") != false && !handle.isNullOrEmpty()) {
                events += LiveEvent.ResumptionHandle(handle)
            }
        }
        if (json.containsKey("goAway")) events += LiveEvent.GoAway
        (json["error"] as? JsonObject)?.let { error ->
            events += LiveEvent.ProviderError(LiveJson.string(error, "message") ?: "Gemini reported an error.")
        }
        return events
    }

    companion object {
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

/**
 * Fud AI's Worker relay to Gemini Live: the Gemini protocol to the Worker URL, identified
 * like the other hosted routes. The Worker swaps in its model and adds `fudVoice` notices.
 * Its setup check is strict, which the Gemini setup above already meets: `generationConfig`
 * holds only response modalities and speech config, declarations only name, description
 * and parameters, and the transcription configs are empty objects.
 */
class HostedLiveWire : GeminiLiveWire() {
    override val provider: LiveProvider = LiveProvider.HOSTED

    override fun request(config: LiveSessionConfig): Request = Request.Builder()
        .url(config.hostedUrl)
        .header(USER_ID_HEADER, config.hostedUserId.orEmpty())
        .header(PLATFORM_HEADER, PLATFORM)
        .build()

    override fun parse(message: String): List<LiveEvent> {
        val json = LiveJson.objectOrNull(message) ?: return emptyList()
        val notice = json["fudVoice"] as? JsonObject ?: return super.parse(message)
        val events = mutableListOf<LiveEvent>()
        (notice["secondsRemaining"] as? JsonPrimitive)?.doubleOrNull?.let {
            events += LiveEvent.HostedStatus(it.toInt().coerceAtLeast(0))
        }
        LiveJson.string(notice, "error")?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.HostedNotice(it) }
        return events
    }

    companion object {
        const val DEFAULT_URL = "wss://fud-ai.app/api/hosted-ai/v1/live"
        const val USER_ID_HEADER = "X-Fud-User-Id"
        const val PLATFORM_HEADER = "X-Fud-Platform"
        const val PLATFORM = "android"

        /** Close codes the Worker uses after the upgrade. */
        const val CLOSE_OUT_OF_MINUTES = 4402
        const val CLOSE_SESSION_LIMIT = 4408
        const val CLOSE_UPSTREAM_FAILURE = 1011
    }
}

// MARK: - OpenAI Realtime

/**
 * OpenAI's Realtime API (GA event names) over a WebSocket.
 *
 * Function calls are collected from `response.function_call_arguments.done` and
 * `response.output_item.done` (deduplicated by call id; a name missing from the arguments
 * event is taken from `response.output_item.added`) and released together at
 * `response.done`, only when the response completed: a reply cut off by the user can
 * still announce calls, and those must not change anything. One `response.create` then
 * follows all of the outputs.
 */
open class OpenAIRealtimeWire : LiveWire {
    override val provider: LiveProvider = LiveProvider.OPENAI
    override val waitsForSetupAcknowledgement = true
    override val toolOutputsNeedResponseCreate = true

    private val functionNames = HashMap<String, String>()
    private val seenCalls = HashSet<String>()
    private val pendingCalls = mutableListOf<LiveToolCall>()

    override fun request(config: LiveSessionConfig): Request = Request.Builder()
        .url(endpoint.toHttpUrl().newBuilder().addQueryParameter("model", config.model).build())
        .header("Authorization", "Bearer ${config.apiKey.orEmpty()}")
        .build()

    protected open val endpoint: String = "https://api.openai.com/v1/realtime"

    override fun setupMessages(config: LiveSessionConfig, resumptionHandle: String?): List<String> {
        val session = buildJsonObject {
            put("type", "realtime")
            put("model", config.model)
            put("instructions", config.instructions)
            putJsonArray("output_modalities") { add(JsonPrimitive("audio")) }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", inputSampleRate) }
                    putJsonObject("turn_detection") {
                        put("type", "semantic_vad")
                        put("create_response", true)
                        put("interrupt_response", true)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", outputSampleRate) }
                    put("voice", config.voice)
                }
            }
            if (config.tools.isNotEmpty()) {
                put("tools", VoiceToolCatalog.realtimeTools(config.tools))
                put("tool_choice", "auto")
            }
        }
        // Transcription goes in its own update, so a transcription problem can't reject
        // the voice, tools and turn-taking settings with it.
        val transcription = buildJsonObject {
            put("type", "realtime")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("transcription") { put("model", TRANSCRIPTION_MODEL) }
                }
            }
        }
        return listOf(sessionUpdate(session), sessionUpdate(transcription))
    }

    protected fun sessionUpdate(session: JsonObject): String = buildJsonObject {
        put("type", "session.update")
        put("session", session)
    }.toString()

    override fun audioMessage(pcm16: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64(pcm16))
    }.toString()

    override fun textMessages(text: String, respond: Boolean): List<String> {
        val item = userMessage(buildJsonArray {
            add(buildJsonObject { put("type", "input_text"); put("text", text) })
        })
        return if (respond) listOf(item, responseCreate()) else listOf(item)
    }

    override fun imageMessages(jpeg: ByteArray): List<String> = listOf(
        userMessage(buildJsonArray {
            add(buildJsonObject {
                put("type", "input_image")
                put("image_url", "data:image/jpeg;base64,${base64(jpeg)}")
            })
        })
    )

    protected fun userMessage(content: JsonArray): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            put("content", content)
        }
    }.toString()

    override fun toolResultMessages(results: List<LiveToolResult>): List<String> = results.map { result ->
        buildJsonObject {
            put("type", "conversation.item.create")
            putJsonObject("item") {
                put("type", "function_call_output")
                put("call_id", result.callId)
                put("output", result.result.toString())
            }
        }.toString()
    }

    override fun responseCreateMessage(): String = responseCreate()

    private fun responseCreate(): String = buildJsonObject { put("type", "response.create") }.toString()

    override fun truncateMessage(itemId: String, audioEndMs: Int): String? = buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", 0)
        put("audio_end_ms", audioEndMs.coerceAtLeast(0))
    }.toString()

    /** Event types whose `delta` is the assistant's words. */
    protected open val transcriptDeltaTypes: Set<String> = setOf(
        "response.output_audio_transcript.delta",
        "response.audio_transcript.delta",
        "response.output_text.delta",
        "response.text.delta"
    )

    override fun parse(message: String): List<LiveEvent> {
        val json = LiveJson.objectOrNull(message) ?: return emptyList()
        val type = LiveJson.string(json, "type") ?: return emptyList()
        return when (type) {
            "session.updated" -> listOf(LiveEvent.Ready)
            "response.output_audio.delta", "response.audio.delta" -> {
                val data = LiveJson.string(json, "delta") ?: return emptyList()
                val pcm = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return emptyList()
                listOf(LiveEvent.Audio(pcm, LiveJson.string(json, "item_id")))
            }
            in transcriptDeltaTypes -> {
                val delta = LiveJson.string(json, "delta")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(LiveEvent.AssistantText(delta))
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.updated" -> {
                // Both carry the whole utterance (xAI's "updated" is cumulative).
                val transcript = LiveJson.string(json, "transcript")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                listOf(LiveEvent.UserText(transcript, replacing = true))
            }
            "input_audio_buffer.speech_started" -> listOf(LiveEvent.UserSpeechStarted)
            "input_audio_buffer.speech_stopped" -> listOf(LiveEvent.UserSpeechStopped)
            "response.created" -> listOf(
                LiveEvent.ResponseStarted(LiveJson.string(json["response"] as? JsonObject, "id"))
            )
            "response.output_item.added" -> {
                rememberFunctionItem(json["item"] as? JsonObject)
                emptyList()
            }
            "response.function_call_arguments.done" -> {
                val itemId = LiveJson.string(json, "item_id")
                val name = LiveJson.string(json, "name")?.takeIf { it.isNotEmpty() }
                    ?: itemId?.let { functionNames[it] }
                    ?: return emptyList()
                val callId = LiveJson.string(json, "call_id")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                collect(callId, name, LiveJson.string(json, "arguments"), LiveJson.string(json, "response_id"))
                emptyList()
            }
            "response.output_item.done" -> {
                val item = json["item"] as? JsonObject ?: return emptyList()
                if (LiveJson.string(item, "type") != "function_call") return emptyList()
                val status = LiveJson.string(item, "status")
                if (status != null && status != "completed") return emptyList()
                val callId = LiveJson.string(item, "call_id")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                val name = LiveJson.string(item, "name")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                collect(callId, name, LiveJson.string(item, "arguments"), LiveJson.string(json, "response_id"))
                emptyList()
            }
            "response.done" -> {
                val response = json["response"] as? JsonObject
                val responseId = LiveJson.string(response, "id")
                // OpenAI also lists the calls here (xAI does not).
                (response?.get("output") as? JsonArray).orEmpty().forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    if (LiveJson.string(item, "type") != "function_call") return@forEach
                    val callId = LiveJson.string(item, "call_id")?.takeIf { it.isNotEmpty() } ?: return@forEach
                    val name = LiveJson.string(item, "name")?.takeIf { it.isNotEmpty() } ?: return@forEach
                    collect(callId, name, LiveJson.string(item, "arguments"), responseId)
                }
                val status = LiveJson.string(response, "status")
                val calls = releaseCalls(responseId)
                val events = mutableListOf<LiveEvent>()
                if (calls.isNotEmpty() && (status == null || status == "completed")) {
                    events += LiveEvent.ToolCalls(calls)
                }
                if (status == "failed") {
                    val details = response?.get("status_details") as? JsonObject
                    val error = details?.get("error") as? JsonObject
                    events += LiveEvent.ProviderError(
                        LiveJson.string(error, "message") ?: "The voice response failed. Please try again."
                    )
                }
                events += LiveEvent.TurnComplete(responseId, successful = status == null || status == "completed")
                events
            }
            "error" -> {
                val error = json["error"]
                val message = (error as? JsonObject)?.let { LiveJson.string(it, "message") }
                    ?: (error as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: "The provider reported an error."
                listOf(LiveEvent.ProviderError(message))
            }
            else -> emptyList()
        }
    }

    private fun rememberFunctionItem(item: JsonObject?) {
        if (LiveJson.string(item, "type") != "function_call") return
        val itemId = LiveJson.string(item, "id") ?: return
        val name = LiveJson.string(item, "name")?.takeIf { it.isNotEmpty() } ?: return
        functionNames[itemId] = name
    }

    private fun collect(callId: String, name: String, arguments: String?, responseId: String?) {
        if (!seenCalls.add(callId)) return
        val args = arguments?.let { LiveJson.objectOrNull(it) } ?: JsonObject(emptyMap())
        pendingCalls += LiveToolCall(callId, name, args, responseId)
    }

    /** The calls of [responseId] (all collected calls when ids are missing). */
    private fun releaseCalls(responseId: String?): List<LiveToolCall> {
        val released = pendingCalls.filter { responseId == null || it.responseId == null || it.responseId == responseId }
        pendingCalls.removeAll(released)
        return released
    }

    companion object {
        /** Transcribes what the user says so it can be shown and saved to Coach. */
        const val TRANSCRIPTION_MODEL = "gpt-transcribe"
    }
}

// MARK: - xAI Grok Voice

/**
 * xAI's Grok Voice (speech to speech) API. It follows the Realtime event shape with a
 * flatter `session.update` (no session type, top-level voice and turn detection) and
 * supports custom function tools in the same flat format. It documents no image input,
 * so camera frames are never sent, and no `tool_choice`, so none is sent.
 */
class XAIVoiceWire : OpenAIRealtimeWire() {
    override val provider: LiveProvider = LiveProvider.XAI
    override val endpoint: String = "https://api.x.ai/v1/realtime"

    // xAI emits text deltas under two names; reading one keeps captions from doubling.
    override val transcriptDeltaTypes: Set<String> = setOf(
        "response.output_audio_transcript.delta",
        "response.audio_transcript.delta",
        "response.output_text.delta"
    )

    override fun setupMessages(config: LiveSessionConfig, resumptionHandle: String?): List<String> {
        val session = buildJsonObject {
            put("voice", config.voice)
            put("instructions", config.instructions)
            putJsonObject("turn_detection") { put("type", "server_vad") }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", inputSampleRate) }
                }
                putJsonObject("output") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", outputSampleRate) }
                }
            }
            if (config.tools.isNotEmpty()) put("tools", VoiceToolCatalog.realtimeTools(config.tools))
        }
        return listOf(sessionUpdate(session))
    }

    override fun imageMessages(jpeg: ByteArray): List<String> = emptyList()
}

// MARK: - JSON helpers

internal object LiveJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun objectOrNull(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun string(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun bool(obj: JsonObject?, key: String): Boolean? = (obj?.get(key) as? JsonPrimitive)?.booleanOrNull
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
