package com.replayer.network.vportal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class VPortalCredentials(
    val apiKey: String,
    val baseUrl: String
)

@Serializable
data class VPortalCategory(
    val title: String,
    val requestParams: Map<String, String>
)

@Serializable
data class VPortalItem(
    val id: Int,
    val title: String,
    val type: String, // "stream" or "multistream"
    val posterUrl: String? = null,
    val previewPosterUrl: String? = null,
    val streamUrl: String? = null,
    val variants: Map<String, String> = emptyMap(),
    val year: Int? = null,
    val durationMinutes: Int? = null,
    val description: String? = null,
    val isFhd: Boolean = false,
    val is4k: Boolean = false,
    val isHdr: Boolean = false,
    val requestParams: Map<String, String> = emptyMap()
)

@Serializable
data class VPortalPageResult(
    val totalCount: Int,
    val items: List<VPortalItem>
)

/**
 * Isolated VPortal JSON API client scaffold for future Re:Player 2.0 integration.
 * Specification: see docs/vportal_api.md.
 * Currently unused by UI until Beta 2.0 development starts.
 */
class VPortalService {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun parsePortalUrl(rawInput: String): VPortalCredentials? {
        val trimmed = rawInput.trim()
        val match = PORTAL_REGEX.matchEntire(trimmed) ?: return null
        val apiKey = match.groupValues[1].trim()
        val url = match.groupValues[2].trim().let { if (it.endsWith("/")) it else "$it/" }
        if (apiKey.isEmpty() || url.isEmpty()) return null
        return VPortalCredentials(apiKey = apiKey, baseUrl = url)
    }

    suspend fun fetchCategories(creds: VPortalCredentials): Result<List<VPortalCategory>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = executePost(creds, emptyMap())
            val items = root["items"]?.jsonArray ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val req = obj["request"]?.jsonObject?.toParamMap().orEmpty()
                VPortalCategory(title = title, requestParams = req)
            }
        }
    }

    suspend fun fetchCategoryItems(
        creds: VPortalCredentials,
        categoryRequest: Map<String, String>,
        offset: Int = 0,
        limit: Int = 30
    ): Result<VPortalPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = categoryRequest.toMutableMap().apply {
                this["offset"] = offset.toString()
                this["limit"] = limit.toString()
            }
            val root = executePost(creds, payload)
            val total = root["count"]?.jsonPrimitive?.intOrNull ?: 0
            val list = root["items"]?.jsonArray?.mapNotNull { parseItem(it.jsonObject) }.orEmpty()
            VPortalPageResult(totalCount = total, items = list)
        }
    }

    suspend fun fetchItemDetail(
        creds: VPortalCredentials,
        itemRequest: Map<String, String>
    ): Result<Pair<VPortalItem?, List<VPortalItem>>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = executePost(creds, itemRequest)
            val mainItem = parseItem(root)
            val episodes = root["items"]?.jsonArray?.mapNotNull { parseItem(it.jsonObject) }.orEmpty()
            Pair(mainItem, episodes)
        }
    }

    private fun executePost(creds: VPortalCredentials, extraParams: Map<String, String>): JsonObject {
        val bodyMap = linkedMapOf<String, JsonElement>("key" to JsonPrimitive(creds.apiKey))
        for ((k, v) in extraParams) {
            val asInt = v.toIntOrNull()
            bodyMap[k] = if (asInt != null) JsonPrimitive(asInt) else JsonPrimitive(v)
        }
        val payloadStr = JsonObject(bodyMap).toString()
        val request = Request.Builder()
            .url(creds.baseUrl)
            .header("User-Agent", "OttPlayer/1.0")
            .header("Accept", "application/json")
            .post(payloadStr.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("VPortal HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
                val msg = root["description"]?.jsonPrimitive?.contentOrNull ?: "VPortal API error"
                error(msg)
            }
            return root
        }
    }

    private fun parseItem(obj: JsonObject): VPortalItem? {
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "stream"
        val fid = obj["fid"]?.jsonPrimitive?.intOrNull ?: 0
        val variantsMap = obj["variants"]?.jsonObject?.mapNotNull { (k, v) ->
            v.jsonPrimitive.contentOrNull?.let { k to it }
        }?.toMap().orEmpty()
        return VPortalItem(
            id = fid,
            title = title,
            type = type,
            posterUrl = obj["img"]?.jsonPrimitive?.contentOrNull,
            previewPosterUrl = obj["imglr"]?.jsonPrimitive?.contentOrNull,
            streamUrl = obj["url"]?.jsonPrimitive?.contentOrNull,
            variants = variantsMap,
            year = obj["year"]?.jsonPrimitive?.intOrNull,
            durationMinutes = obj["duration"]?.jsonPrimitive?.intOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            isFhd = obj["fhd"]?.jsonPrimitive?.booleanOrNull == true,
            is4k = obj["4k"]?.jsonPrimitive?.booleanOrNull == true,
            isHdr = obj["hdr"]?.jsonPrimitive?.booleanOrNull == true,
            requestParams = obj["request"]?.jsonObject?.toParamMap().orEmpty()
        )
    }

    private fun JsonObject.toParamMap(): Map<String, String> = mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
    }.toMap()

    companion object {
        private val PORTAL_REGEX = Regex("""^portal::\[key:([^]]+)](https?://.+)$""", RegexOption.IGNORE_CASE)
    }
}
