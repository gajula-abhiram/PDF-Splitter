package com.abhiram.photoq

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CmdKeyProvider(private val context: Context) {
    private val prefs = context.getSharedPreferences("photoq", Context.MODE_PRIVATE)

    fun setCameraTree(uri: Uri) {
        prefs.edit().putString("camera_tree", uri.toString()).apply()
    }

    fun cameraTree(): Uri? = prefs.getString("camera_tree", null)?.let(Uri::parse)

    fun readKey(): String {
        val tree = cameraTree() ?: error("Camera folder access not configured. Select DCIM/Camera first.")
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("Camera folder permission is no longer valid.")
        val file = root.findFile("cmd.txt") ?: error("cmd.txt was not found in the selected Camera folder.")
        val raw = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Unable to read cmd.txt")
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: error("cmd.txt is empty")
        val value = when {
            '=' in line -> line.substringAfter('=').trim()
            ':' in line && line.substringBefore(':').contains("key", ignoreCase = true) -> line.substringAfter(':').trim()
            else -> line
        }.trim().trim('"', '\'', ' ')
        if (value.length < 8) error("cmd.txt does not contain a valid-looking API key")
        return value
    }
}

data class ParsedAnswer(val questionNumber: String?, val answers: List<Int>, val answerText: String?)
data class ParsedEnvelope(val status: String, val results: List<ParsedAnswer>)

class CommandCodeClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val keyProvider = CmdKeyProvider(context)

    suspend fun answer(images: List<CameraPhoto>): Pair<String, ParsedEnvelope> {
        var last: Throwable? = null
        repeat(4) { attempt ->
            try { return doAnswer(images) }
            catch (t: Throwable) {
                last = t
                val retryable = t is HttpFailure && (t.code == 429 || t.code in 500..599)
                if (!retryable || attempt == 3) throw t
                delay((1000L shl attempt).coerceAtMost(8000L))
            }
        }
        throw last ?: IllegalStateException("Unknown request failure")
    }

    private fun doAnswer(images: List<CameraPhoto>): Pair<String, ParsedEnvelope> {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", PROMPT))
        for (photo in images) {
            val uri = Uri.parse(photo.uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read image: ${photo.uri}")
            val mime = photo.mime.ifBlank { context.contentResolver.getType(uri) ?: "image/jpeg" }
            val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            content.put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:$mime;base64,$data")))
        }
        val body = JSONObject()
            .put("model", "google/gemini-3.7-flash")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val req = Request.Builder()
            .url("https://api.commandcode.ai/provider/v1/chat/completions")
            .header("Authorization", "Bearer ${keyProvider.readKey()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: text } catch (_: Throwable) { text }
            throw HttpFailure(resp.code, msg.ifBlank { "HTTP ${resp.code}" })
        }
        val root = JSONObject(text)
        val message = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val raw = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val p = c.optJSONObject(i) ?: continue
                    if (p.optString("type") == "text") append(p.optString("text"))
                }
            }
            else -> c?.toString().orEmpty()
        }
        val parsedJson = Python.getInstance().getModule("parser").callAttr("parse_response", raw).toString()
        val parsedObj = JSONObject(parsedJson)
        val arr = parsedObj.getJSONArray("results")
        val results = mutableListOf<ParsedAnswer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val aa = o.getJSONArray("answers")
            val answers = (0 until aa.length()).map { aa.getInt(it) }
            results += ParsedAnswer(
                o.optString("question_number", "").ifBlank { null },
                answers,
                o.optString("answer_text", "").ifBlank { null }
            )
        }
        return raw to ParsedEnvelope(parsedObj.optString("status", "unparsed"), results)
    }

    companion object {
        val PROMPT = """
Carefully inspect every supplied image. All images in this request belong to the same question/context unless the image itself clearly contains several complete separate questions. Read the complete question, every answer choice, and any diagrams, equations, tables, or continuation images. Determine the visible question number only from the image content; never infer it from filenames or image order. If the question number is unclear, return null. Solve carefully. A question may have one or multiple correct options; return every correct option. Never confuse the question number with an answer option number.
Return compact JSON only. For one question use:
{"question_number":"24","answers":["A","C"],"answer_text":"optional short answer text","multiple_answers":true}
If one image clearly contains multiple complete questions, return:
{"results":[{"question_number":"21","answers":["B"],"answer_text":"...","multiple_answers":false}]}
Do not invent missing information.
        """.trimIndent()
    }
}

class HttpFailure(val code: Int, message: String) : Exception(message)
