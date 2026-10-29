/*
 * Copyright 2018 Google Inc.
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
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.muzei.unsplash.BuildConfig.UNSPLASH_AUTHORITY
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import java.io.IOException

class UnsplashExampleWorker(
        context: Context,
        workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "UnsplashExample"

        internal fun enqueueLoad(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueue(OneTimeWorkRequestBuilder<UnsplashExampleWorker>()
                    .setConstraints(Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .build())
        }
    }

    override fun doWork(): Result {
        // Each load draws from one of five feeds at random with equal weight:
        // Met ukiyo-e landscapes, or one of the four Flickr feeds (favorites,
        // following, explore, family), which popularPhotos() picks between.
        if ((0 until 5).random() == 0) {
            return loadMetPrints()
        }
        return loadFlickrPhotos()
    }

    private fun loadMetPrints(): Result {
        val artwork = try {
            MetSource.landscapePrints(applicationContext)
        } catch (e: IOException) {
            Log.w(TAG, "Error reading Met response", e)
            return Result.retry()
        }
        if (artwork.isEmpty()) {
            Log.w(TAG, "No landscape prints returned from the Met API.")
            return Result.retry()
        }
        ProviderContract.getProviderClient(applicationContext, UNSPLASH_AUTHORITY)
                .addArtwork(artwork)
        return Result.success()
    }

    private fun loadFlickrPhotos(): Result {
        val photos = try {
            UnsplashService.popularPhotos()
        } catch (e: IOException) {
            Log.w(TAG, "Error reading Unsplash response", e)
            return Result.retry()
        }

        if (photos.isEmpty()) {
            Log.w(TAG, "No photos returned from API.")
            return Result.failure()
        }

        val providerClient = ProviderContract.getProviderClient(
                applicationContext, UNSPLASH_AUTHORITY)
        val attributionString = applicationContext.getString(R.string.attribution)
        providerClient.addArtwork(photos.filter { photo ->
            photo.getBestUrl() != null
        }.map { photo ->
            Artwork(
                    token = photo.id,
                    title = photo.title ?: attributionString,
                    byline = photo.owner,
                    attribution = if (photo.title != null) attributionString else null,
                    persistentUri = photo.getBestUrl()?.toUri(),
                    webUri = "https://www.flickr.com/photos/${photo.owner}/${photo.id}".toUri())
        })
        return Result.success()
    }
}
