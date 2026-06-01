package com.my.knowledge.data.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.my.knowledge.ui.KnowledgeManager
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

data class VoiceRecognitionState(
    val isRecording: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String = "语音待命",
    val partialTranscript: String = "",
    val rms: Float = 0f,
    val lastVoiceAtMillis: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Volcengine realtime ASR client.
 *
 * Protocol shape follows the Volcengine streaming WebSocket guidance:
 * binary WebSocket frames, 16 kHz mono PCM chunks, server partial results,
 * manual finish frame, and client-side 30s silence guard.
 */
class VolcengineVoiceService(private val context: Context) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(VoiceRecognitionState())
    val stateFlow: StateFlow<VoiceRecognitionState> = _state.asStateFlow()

    private val _finalTranscriptFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val finalTranscriptFlow: SharedFlow<String> = _finalTranscriptFlow.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var recordingJob: Job? = null
    private var sequence = 1
    @Volatile private var shouldRecord = false
    @Volatile private var lastVoiceAt = 0L

    private val config get() = KnowledgeManager.modelConfig

    fun startRealtimeTranscription() {
        if (shouldRecord) return
        val apiKey = config.voiceApiKey.trim()
        val appId = config.voiceAppId.trim()
        if (apiKey.isBlank() || appId.isBlank()) {
            _state.value = VoiceRecognitionState(
                statusMessage = "请先在设置中配置火山引擎语音 App ID 和 API Key",
                errorMessage = "语音 App ID 或 API Key 未配置"
            )
            return
        }

        shouldRecord = true
        lastVoiceAt = System.currentTimeMillis()
        sequence = 1
        _state.value = VoiceRecognitionState(
            isRecording = true,
            statusMessage = "正在连接语音服务...",
            lastVoiceAtMillis = lastVoiceAt
        )

        val request = Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")
            .header("X-Api-App-Key", appId)
            .header("X-Api-Access-Key", apiKey)
            .header("X-Api-Resource-Id", "volc.bigasr.sauc.duration")
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.update {
                    it.copy(isConnected = true, statusMessage = "正在听写，中英双语实时识别中")
                }
                sendFullClientRequest(webSocket)
                startAudioCapture(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleJsonPayload(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markStopped("语音识别已停止")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                markStopped("语音识别连接失败：${t.message ?: "未知错误"}", t.message)
            }
        })
    }

    fun stopRecording() {
        if (!shouldRecord && !_state.value.isRecording) return
        shouldRecord = false
        _state.update { it.copy(statusMessage = "正在停止语音识别...") }
        recordingJob?.cancel()
        sendFinishFrame()
        webSocket?.close(1000, "client stopped")
        webSocket = null
        markStopped("语音识别已停止")
    }

    fun release() {
        stopRecording()
        serviceScope.cancel()
    }

    private fun sendFullClientRequest(webSocket: WebSocket) {
        val payload = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "android_${UUID.randomUUID().toString().take(8)}")
            })
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", SAMPLE_RATE)
                put("sample_rate", SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
                put("language", "zh-CN")
            })
            put("request", JSONObject().apply {
                put("model_name", "bigmodel")
                put("result_type", "partial")
                put("show_utterances", true)
                put("enable_punc", true)
                put("enable_itn", true)
                put("enable_ddc", true)
            })
        }.toString().toByteArray(Charsets.UTF_8)

        webSocket.send(buildFrame(FULL_CLIENT_REQUEST, POS_SEQUENCE, payload, sequence++).toByteString())
    }

    private fun startAudioCapture(webSocket: WebSocket) {
        recordingJob = serviceScope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize.coerceAtLeast(AUDIO_FRAME_BYTES * 2)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                markStopped("麦克风初始化失败", "AudioRecord 初始化失败")
                return@launch
            }

            val buffer = ByteArray(AUDIO_FRAME_BYTES)
            try {
                audioRecord.startRecording()
                while (shouldRecord && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rms = calculateRms(buffer, read)
                        val now = System.currentTimeMillis()
                        if (rms >= VOICE_RMS_THRESHOLD) {
                            lastVoiceAt = now
                        }
                        _state.update {
                            it.copy(
                                isRecording = true,
                                rms = rms,
                                lastVoiceAtMillis = lastVoiceAt,
                                statusMessage = if (rms >= VOICE_RMS_THRESHOLD) {
                                    "检测到语音，正在实时转写"
                                } else {
                                    "正在听写，30 秒无人声将自动停止"
                                }
                            )
                        }

                        webSocket.send(
                            buildFrame(
                                AUDIO_ONLY_REQUEST,
                                POS_SEQUENCE,
                                buffer.copyOf(read),
                                sequence++
                            ).toByteString()
                        )

                        if (now - lastVoiceAt >= SILENCE_TIMEOUT_MS) {
                            shouldRecord = false
                            withContext(Dispatchers.Main) {
                                _state.update { it.copy(statusMessage = "30 秒未检测到人声，已自动停止") }
                            }
                            break
                        }
                    }
                    delay(20)
                }
            } catch (e: SecurityException) {
                markStopped("缺少麦克风权限", e.message)
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture failure", e)
                markStopped("录音失败：${e.message ?: "未知错误"}", e.message)
            } finally {
                runCatching {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                }
                audioRecord.release()
                sendFinishFrame()
                webSocket.close(1000, "audio finished")
            }
        }
    }

    private fun sendFinishFrame() {
        val socket = webSocket ?: return
        runCatching {
            socket.send(buildFrame(AUDIO_ONLY_REQUEST, NEG_SEQUENCE, ByteArray(0), -sequence).toByteString())
        }
    }

    private fun buildFrame(messageType: Int, flags: Int, rawPayload: ByteArray, seq: Int): ByteArray {
        val payload = gzip(rawPayload)
        val header = byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or DEFAULT_HEADER_SIZE).toByte(),
            ((messageType shl 4) or flags).toByte(),
            ((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP).toByte(),
            0x00
        )
        return ByteBuffer.allocate(header.size + 8 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(seq)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        val data = bytes.toByteArray()
        if (data.size < 8) return
        runCatching {
            val first = data[0].toInt() and 0xFF
            val headerSize = (first and 0x0F) * 4
            val messageType = (data[1].toInt() and 0xF0) shr 4
            val flags = data[1].toInt() and 0x0F
            val compression = data[2].toInt() and 0x0F
            var offset = headerSize

            if (hasSequence(flags) && data.size >= offset + 4) {
                offset += 4
            }

            if (messageType == SERVER_ERROR_RESPONSE && data.size >= offset + 8) {
                val code = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                val errorPayloadSize = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                val errorPayload = data.copyOfRange(offset, (offset + errorPayloadSize).coerceAtMost(data.size))
                val errorText = String(maybeGunzip(errorPayload, compression), Charsets.UTF_8)
                markStopped("语音接口返回错误：$code", errorText)
                return
            }

            if (data.size < offset + 4) return
            val payloadSize = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
            if (payloadSize <= 0 || data.size < offset) return

            val payload = data.copyOfRange(offset, (offset + payloadSize).coerceAtMost(data.size))
            val jsonText = String(maybeGunzip(payload, compression), Charsets.UTF_8)
            handleJsonPayload(jsonText)
        }.onFailure {
            Log.e(TAG, "Failed to parse ASR frame", it)
        }
    }

    private fun handleJsonPayload(text: String) {
        runCatching {
            val json = JSONObject(text)
            val transcript = findTranscript(json).trim()
            if (transcript.isBlank()) return
            if (isFinalPayload(json)) {
                serviceScope.launch { _finalTranscriptFlow.emit(transcript) }
                _state.update { it.copy(partialTranscript = "") }
            } else {
                _state.update { it.copy(partialTranscript = transcript) }
            }
        }.onFailure {
            Log.e(TAG, "Failed to parse ASR JSON: $text", it)
        }
    }

    private fun findTranscript(value: Any?): String {
        return when (value) {
            is JSONObject -> {
                val directKeys = listOf("text", "utterance", "transcript", "sentence")
                directKeys.firstNotNullOfOrNull { key ->
                    value.optString(key).takeIf { it.isNotBlank() }
                } ?: value.keys().asSequence()
                    .mapNotNull { key -> findTranscript(value.opt(key)).takeIf { it.isNotBlank() } }
                    .firstOrNull()
                    .orEmpty()
            }
            is JSONArray -> (0 until value.length())
                .mapNotNull { index -> findTranscript(value.opt(index)).takeIf { it.isNotBlank() } }
                .joinToString("")
            else -> ""
        }
    }

    private fun isFinalPayload(value: Any?): Boolean {
        return when (value) {
            is JSONObject -> {
                listOf("definite", "is_final", "final").any { key ->
                    value.has(key) && value.optBoolean(key, false)
                } || value.keys().asSequence().any { key -> isFinalPayload(value.opt(key)) }
            }
            is JSONArray -> (0 until value.length()).any { index -> isFinalPayload(value.opt(index)) }
            else -> false
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
            sum += sample * sample
            samples++
            index += 2
        }
        if (samples == 0) return 0f
        return (sqrt(sum / samples) / Short.MAX_VALUE).toFloat()
    }

    private fun hasSequence(flags: Int): Boolean {
        return flags == POS_SEQUENCE || flags == NEG_SEQUENCE || flags == NEG_WITH_SEQUENCE
    }

    private fun markStopped(message: String, error: String? = null) {
        shouldRecord = false
        recordingJob?.cancel()
        webSocket = null
        _state.update {
            it.copy(
                isRecording = false,
                isConnected = false,
                statusMessage = message,
                rms = 0f,
                errorMessage = error
            )
        }
    }

    private fun gzip(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }

    private fun maybeGunzip(input: ByteArray, compression: Int): ByteArray {
        if (compression != COMPRESSION_GZIP) return input
        return GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
    }

    companion object {
        private const val TAG = "VolcengineVoice"
        private const val SAMPLE_RATE = 16_000
        private const val AUDIO_FRAME_BYTES = 3_200
        private const val SILENCE_TIMEOUT_MS = 30_000L
        private const val VOICE_RMS_THRESHOLD = 0.012f

        private const val PROTOCOL_VERSION = 1
        private const val DEFAULT_HEADER_SIZE = 1
        private const val FULL_CLIENT_REQUEST = 1
        private const val AUDIO_ONLY_REQUEST = 2
        private const val SERVER_ERROR_RESPONSE = 15
        private const val POS_SEQUENCE = 1
        private const val NEG_SEQUENCE = 2
        private const val NEG_WITH_SEQUENCE = 3
        private const val SERIALIZATION_JSON = 1
        private const val COMPRESSION_GZIP = 1
    }
}
