/*
 * Copyright 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.muzei.unsplash

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal client for The Metropolitan Museum of Art Collection API.
 *
 * See https://metmuseum.github.io/ for the API documentation.
 */
internal class RateLimitedException(message: String) : IOException(message)

internal object MetService {
    private const val BASE_URL = "https://collectionapi.metmuseum.org/public/collection/"

    /**
     * Subject tags the Met applies to its objects that indicate a landscape print.
     * Each tag is searched separately and the results are merged.
     */
    internal val LANDSCAPE_TAGS = listOf(
        "Landscapes",
        "Mountains",
        "Rivers",
        "Lakes",
        "Seas",
        "Waves",
        "Waterfalls",
        "Snow",
        "Rain",
        "Moon",
        "Bridges",
        "Temples",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "Muzei Met Ukiyo-e Landscapes source")
                    .build())
            }
            .build()
    }

    @Throws(IOException::class)
    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                // The Met sits behind bot protection that blocks clients making
                // too many requests; back off rather than hammering it further.
                throw RateLimitedException("HTTP ${response.code} for $url")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body?.string() ?: throw IOException("Empty body for $url")
            return try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IOException("Invalid JSON for $url", e)
            }
        }
    }

    /**
     * Search Japanese prints that carry the given subject tag. The API ignores
     * the public domain parameter, so that is filtered on each object instead.
     */
    @Throws(IOException::class)
    fun searchJapanesePrintsByTag(tag: String, limit: Int = 500, offset: Int = 0): List<Long> {
        val url = "${BASE_URL}v1.1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", tag)
            .addQueryParameter("tags", "true")
            .addQueryParameter("geoLocation", "Japan")
            .addQueryParameter("medium", "Prints")
            .addQueryParameter("hasImages", "true")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val json = getJson(url.toString())
        val ids = json.optJSONArray("objectIDs") ?: return emptyList()
        return List(ids.length()) { ids.getLong(it) }
    }

    @Throws(IOException::class)
    fun getObject(objectId: Long): MetObject {
        val json = getJson("${BASE_URL}v1/objects/$objectId")
        val overall = json.optJSONArray("measurements")?.let { measurements ->
            var first: JSONObject? = null
            for (i in 0 until measurements.length()) {
                val m = measurements.optJSONObject(i) ?: continue
                if (first == null) first = m
                if (m.optString("elementName") == "Overall") {
                    first = m
                    break
                }
            }
            first?.optJSONObject("elementMeasurements")
        }
        return MetObject(
            objectID = json.optLong("objectID", objectId),
            isPublicDomain = json.optBoolean("isPublicDomain", false),
            primaryImage = json.optString("primaryImage").takeIf { it.isNotBlank() },
            title = json.optString("title").takeIf { it.isNotBlank() },
            artistDisplayName = json.optString("artistDisplayName").takeIf { it.isNotBlank() },
            objectDate = json.optString("objectDate").takeIf { it.isNotBlank() },
            classification = json.optString("classification").takeIf { it.isNotBlank() },
            width = overall?.optDouble("Width")?.takeIf { !it.isNaN() },
            height = overall?.optDouble("Height")?.takeIf { !it.isNaN() },
            objectURL = json.optString("objectURL").takeIf { it.isNotBlank() },
        )
    }

    data class MetObject(
        val objectID: Long,
        val isPublicDomain: Boolean,
        val primaryImage: String?,
        val title: String?,
        val artistDisplayName: String?,
        val objectDate: String?,
        val classification: String?,
        /** Overall width in centimetres, if known */
        val width: Double?,
        /** Overall height in centimetres, if known */
        val height: Double?,
        val objectURL: String?,
    ) {
        /**
         * Only public domain prints with an image whose overall width exceeds
         * its height (landscape orientation) are usable as wallpaper.
         */
        val isLandscapePrint: Boolean
            get() = isPublicDomain &&
                primaryImage != null &&
                classification?.contains("Prints", ignoreCase = true) == true &&
                width != null && height != null && width > height
    }
}
