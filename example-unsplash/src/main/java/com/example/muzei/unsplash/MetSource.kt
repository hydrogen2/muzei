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

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.apps.muzei.api.provider.Artwork
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Public domain Japanese landscape woodblock prints from
 * The Metropolitan Museum of Art Collection API.
 */
internal object MetSource {
    private const val TAG = "MetSource"
    private const val PREFS = "met_ukiyoe"
    private const val PREF_IDS = "candidate_ids"
    private const val PREF_IDS_TIME = "candidate_ids_time"
    private val IDS_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

    /** How many landscape prints to add per load */
    private const val TARGET_ARTWORK_COUNT = 10
    /** Upper bound on object lookups per load, since many tag hits are vertical */
    private const val MAX_OBJECT_LOOKUPS = 40
    /** Pause between requests so the Met's bot protection never sees a burst */
    private const val REQUEST_DELAY_MS = 300L

    /**
     * Fetch a random batch of landscape prints.
     *
     * @throws IOException if the candidate search fails or the API rate limits us
     */
    @Throws(IOException::class)
    fun landscapePrints(context: Context): List<Artwork> {
        val candidateIds = loadCandidateIds(context)
        if (candidateIds.isEmpty()) {
            throw IOException("No candidate objects returned from the Met API")
        }
        val attribution = context.getString(R.string.attribution_met)
        val artwork = mutableListOf<Artwork>()
        var lookups = 0
        for (objectId in candidateIds.shuffled()) {
            if (artwork.size >= TARGET_ARTWORK_COUNT || lookups >= MAX_OBJECT_LOOKUPS) {
                break
            }
            if (lookups > 0) {
                Thread.sleep(REQUEST_DELAY_MS)
            }
            lookups++
            val metObject = try {
                MetService.getObject(objectId)
            } catch (e: RateLimitedException) {
                Log.w(TAG, "Rate limited by the Met API, will retry later", e)
                break
            } catch (e: IOException) {
                Log.w(TAG, "Error loading Met object $objectId", e)
                continue
            }
            if (!metObject.isLandscapePrint) {
                continue
            }
            val byline = listOfNotNull(metObject.artistDisplayName, metObject.objectDate)
                .joinToString(", ")
            artwork += Artwork(
                token = "met:${metObject.objectID}",
                title = metObject.title ?: attribution,
                byline = byline.ifBlank { null },
                attribution = attribution,
                persistentUri = metObject.primaryImage?.toUri(),
                webUri = (metObject.objectURL
                    ?: "https://www.metmuseum.org/art/collection/search/${metObject.objectID}").toUri(),
            )
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Found ${artwork.size} landscape prints after $lookups lookups")
        }
        return artwork
    }

    /**
     * The merged set of object ids for every landscape tag. Searching costs one
     * request per tag, so the ids are cached for a week.
     */
    @Throws(IOException::class)
    private fun loadCandidateIds(context: Context): List<Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedTime = prefs.getLong(PREF_IDS_TIME, 0L)
        val cached = prefs.getString(PREF_IDS, null)
        if (cached != null && System.currentTimeMillis() - cachedTime < IDS_MAX_AGE_MS) {
            val ids = cached.split(',').mapNotNull { it.toLongOrNull() }
            if (ids.isNotEmpty()) {
                return ids
            }
        }

        val ids = linkedSetOf<Long>()
        MetService.LANDSCAPE_TAGS.forEachIndexed { index, tag ->
            if (index > 0) {
                Thread.sleep(REQUEST_DELAY_MS)
            }
            ids += MetService.searchJapanesePrintsByTag(tag)
        }
        if (ids.isNotEmpty()) {
            prefs.edit {
                putString(PREF_IDS, ids.joinToString(","))
                putLong(PREF_IDS_TIME, System.currentTimeMillis())
            }
        }
        return ids.toList()
    }
}
