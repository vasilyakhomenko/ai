package com.example.aichat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
)

@Serializable
data class ImageUrl(val url: String)

@Serializable
data class ApiChatMessage(
    val role: String,
    // OpenAI: string OR array of parts. We serialize carefully in request builder.
    val content: List<ContentPart>
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.7
)

@Serializable
data class ChatChoice(val message: ApiMessageResponse)

@Serializable
data class ApiMessageResponse(
    val role: String,
    val content: String? = null
)

@Serializable
data class ChatResponse(val choices: List<ChatChoice>)

interface ChatApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse
}

class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response: Response? = null
        var lastError: IOException? = null
        while (attempt <= maxRetries) {
            try {
                response?.close()
                response = chain.proceed(chain.request())
                if (response.code !in listOf(502, 503, 504) || attempt == maxRetries) {
                    return response
                }
                response.close()
                Thread.sleep(1000L * (attempt + 1))
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries) throw e
                Thread.sleep(1000L * (attempt + 1))
            }
            attempt++
        }
        throw lastError ?: IOException("Сервер недоступен")
    }
}

object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun create(baseUrl: String): ChatApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(3))
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // Нормализация URL для Claudech / OpenAI
        var clean = baseUrl.trim().removeSuffix("/")
        if (clean.endsWith("/chat/completions")) {
            clean = clean.removeSuffix("/chat/completions")
        }
        // Claudech: https://api.claudech.com/v1
        if (!clean.endsWith("/v1") && clean.contains("claudech.com") && !clean.contains("/v1")) {
            clean = "$clean/v1"
        }
        if (!clean.endsWith("/")) clean = "$clean/"

        return Retrofit.Builder()
            .baseUrl(clean)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatApi::class.java)
    }

    fun friendlyError(e: Throwable): String {
        return when (e) {
            is HttpException -> {
                val body = try { e.response()?.errorBody()?.string()?.take(200) } catch (_: Exception) { null }
                when (e.code()) {
                    401 -> "Неверный API-ключ (401). Проверьте ключ Claudech / OpenAI."
                    403 -> "Доступ запрещён (403). Подтвердите email или пополните баланс на Claudech."
                    404 -> "Не найдено (404). Base URL должен быть вида https://api.claudech.com/v1 (с /v1)."
                    429 -> "Лимит запросов (429). Подождите."
                    402 -> "Недостаточно средств (402). Пополните баланс Claudech."
                    500 -> "Ошибка сервера (500)."
                    502, 503, 504 -> "Сервер временно недоступен (${e.code()}). Повторите через минуту. Для Claudech: URL = https://api.claudech.com/v1 , модель = claude-opus-5-5 (не gpt-4o)."
                    else -> "HTTP ${e.code()}${if (body != null) ": $body" else ""}"
                }
            }
            is IOException -> "Сеть: ${e.message}"
            else -> e.message ?: "Неизвестная ошибка"
        }
    }
}



@Serializable
data class StreamDelta(val content: String? = null)

@Serializable
data class StreamChoice(val delta: StreamDelta? = null, val index: Int = 0)

@Serializable
data class StreamChunk(val choices: List<StreamChoice> = emptyList())

/**
 * SSE streaming for OpenAI-compatible /chat/completions
 */
object StreamClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var currentCall: okhttp3.Call? = null

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun normalizeBase(baseUrl: String): String {
        var clean = baseUrl.trim().removeSuffix("/")
        if (clean.endsWith("/chat/completions")) {
            clean = clean.removeSuffix("/chat/completions")
        }
        if (!clean.endsWith("/v1") && clean.contains("claudech.com") && !clean.contains("/v1")) {
            clean = "$clean/v1"
        }
        return clean
    }

    /**
     * @param onDelta called on main-unsafe thread with each text piece
     * @return full assembled text
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        onDelta: (String) -> Unit
    ): String {
        val base = normalizeBase(baseUrl)
        val url = "$base/chat/completions"

        // Build JSON manually for content as string when single text part
        val messagesJson = buildJsonArray {
            for (m in messages) {
                add(buildJsonObject {
                    put("role", m.role)
                    val parts = m.content
                    if (parts.size == 1 && parts[0].type == "text" && parts[0].imageUrl == null) {
                        put("content", parts[0].text ?: "")
                    } else {
                        put("content", buildJsonArray {
                            for (p in parts) {
                                add(buildJsonObject {
                                    put("type", p.type)
                                    if (p.text != null) put("text", p.text)
                                    if (p.imageUrl != null) {
                                        put("image_url", buildJsonObject {
                                            put("url", p.imageUrl.url)
                                        })
                                    }
                                })
                            }
                        })
                    }
                })
            }
        }
        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", 0.7)
            put("messages", messagesJson)
        }

        val media = "application/json; charset=utf-8".toMediaType()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(media))
            .build()

        val full = StringBuilder()
        val call = client.newCall(request)
        currentCall = call
        try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(300) ?: ""
                val code = response.code
                throw IOException("HTTP $code $err")
            }
            val source = response.body?.source() ?: throw IOException("Empty body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]" || data.isEmpty()) break
                try {
                    val chunk = json.decodeFromString<StreamChunk>(data)
                    val piece = chunk.choices.firstOrNull()?.delta?.content
                    if (!piece.isNullOrEmpty()) {
                        full.append(piece)
                        onDelta(piece)
                    }
                } catch (_: Exception) {
                    // skip malformed chunk
                }
            }
        }
        return full.toString()
        } finally {
            currentCall = null
        }
    }
}
