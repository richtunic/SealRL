package com.junkfood.seal.util

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads the public photo and video URLs embedded in an Instagram post page. */
internal object InstagramEmbedParser {
    data class Media(
        val id: String,
        val url: String,
        val thumbnail: String?,
        val isVideo: Boolean,
        val title: String,
        val author: String?,
    )

    fun parse(html: String, shortcode: String): List<Media>? {
        var searchFrom = 0
        while (true) {
            val handleStart = html.indexOf("s.handle(", searchFrom)
            if (handleStart < 0) return null
            val jsonStart = html.indexOf('{', handleStart + "s.handle(".length)
            if (jsonStart < 0) return null
            val jsonEnd = findObjectEnd(html, jsonStart)
            if (jsonEnd < 0) return null
            searchFrom = jsonEnd + 1

            val source = html.substring(jsonStart, searchFrom)
            if (!source.contains("gql_data")) continue
            val data = runCatching { Json.parseToJsonElement(source) }.getOrNull() ?: continue
            val post = findPost(data, shortcode) ?: continue
            val author = post.obj("owner")?.str("username")
            val children = post.obj("edge_sidecar_to_children")?.array("edges")
            val nodes = children?.mapNotNull { (it as? JsonObject)?.obj("node") } ?: listOf(post)
            val media = nodes.mapIndexedNotNull { index, node ->
                val isVideo = node.bool("is_video")
                val mediaUrl = node.str(if (isVideo) "video_url" else "display_url")
                    ?.takeIf(::isSafeMediaUrl) ?: return@mapIndexedNotNull null
                Media(
                    id = node.str("id") ?: "${shortcode}_$index",
                    url = mediaUrl,
                    thumbnail = node.str("display_url")?.takeIf(::isSafeMediaUrl),
                    isVideo = isVideo,
                    title = (if (isVideo) "Video" else "Foto") +
                        if (nodes.size > 1) " ${index + 1}" else "",
                    author = author,
                )
            }
            if (media.size == nodes.size && media.isNotEmpty()) return media
        }
    }

    private fun findPost(value: JsonElement, shortcode: String, depth: Int = 0): JsonObject? {
        if (depth > 12) return null
        when (value) {
            is JsonObject -> {
                val post = value.obj("gql_data")?.obj("shortcode_media")
                if (post?.str("shortcode") == shortcode) return post
                value.values.forEach { findPost(it, shortcode, depth + 1)?.let { post -> return post } }
            }
            is JsonArray -> value.forEach { findPost(it, shortcode, depth + 1)?.let { post -> return post } }
            is JsonPrimitive -> {
                if (value.isString && value.content.length < 750_000 && value.content.contains("gql_data")) {
                    val nested = runCatching { Json.parseToJsonElement(value.content) }.getOrNull()
                    if (nested != null) return findPost(nested, shortcode, depth + 1)
                }
            }
        }
        return null
    }

    private fun findObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun isSafeMediaUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" &&
            (host == "cdninstagram.com" || host.endsWith(".cdninstagram.com") ||
                host == "fbcdn.net" || host.endsWith(".fbcdn.net"))
    }.getOrDefault(false)

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = str(key) == "true"
}
