package com.dbexplorer.sources

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Executes a GET and returns the response; the caller must close it. Throws on non-2xx. */
    fun get(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DBExplorer/1.0 (Android; personal use)")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code for ${url.substringBefore('?')}")
        }
        return response
    }

    fun <T> json(url: String, block: (JsonReader) -> T): T =
        get(url).use { r -> JsonReader(r.body!!.charStream()).use(block) }
}

/** Reads the current JSON value as a string, or null; nested objects/arrays are skipped. */
fun JsonReader.nextScalar(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> { skipValue(); null }
    else -> nextString()
}

/** Iterates the keys of the current object. */
inline fun JsonReader.forEachKey(block: (String) -> Unit) {
    beginObject()
    while (hasNext()) block(nextName())
    endObject()
}

/** Iterates the elements of the current array, or skips the value if it is not an array. */
inline fun JsonReader.forEachElement(block: () -> Unit) {
    if (peek() != JsonToken.BEGIN_ARRAY) { skipValue(); return }
    beginArray()
    while (hasNext()) block()
    endArray()
}

/** Reads an array of objects and collects the [field] string of each one. */
fun JsonReader.namesOf(field: String, nestedIn: String? = null): List<String> {
    val out = mutableListOf<String>()
    forEachElement {
        if (peek() != JsonToken.BEGIN_OBJECT) { skipValue(); return@forEachElement }
        forEachKey { k ->
            when {
                nestedIn != null && k == nestedIn && peek() == JsonToken.BEGIN_OBJECT ->
                    forEachKey { k2 -> if (k2 == field) nextScalar()?.let(out::add) else skipValue() }
                nestedIn == null && k == field -> nextScalar()?.let(out::add)
                else -> skipValue()
            }
        }
    }
    return out
}

fun yearOf(text: String?): Int? = text?.let { Regex("(18|19|20)\\d{2}").find(it)?.value?.toInt() }

fun normalizeName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
