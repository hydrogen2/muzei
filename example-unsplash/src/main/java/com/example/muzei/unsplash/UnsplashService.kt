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

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.create
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import se.akerfeldt.okhttp.signpost.OkHttpOAuthConsumer
import se.akerfeldt.okhttp.signpost.SigningInterceptor

internal interface UnsplashService {

    companion object {
        // OAuth credentials
        private const val OAUTH_TOKEN = "72157720962156342-cc7f723e08b19c2b"
        private const val OAUTH_TOKEN_SECRET = "9d3aaf1845854918"
        private const val CONSUMER_SECRET = "27967225db7fce22"

        private fun createService() : UnsplashService {
            val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        var request = chain.request()
                        val url = request.url.newBuilder()
                                .addQueryParameter("api_key", CONSUMER_KEY).build()
                        request = request.newBuilder().url(url).build()
                        chain.proceed(request)
                    }
                    .build()

            val retrofit = Retrofit.Builder()
                    .baseUrl("https://www.flickr.com/services/rest/")
                    .client(okHttpClient)
                    .addConverterFactory(MoshiConverterFactory.create().asLenient())
                    .build()

            return retrofit.create()
        }

        private fun createOAuthService() : UnsplashService {
            val consumer = OkHttpOAuthConsumer(CONSUMER_KEY, CONSUMER_SECRET)
            consumer.setTokenWithSecret(OAUTH_TOKEN, OAUTH_TOKEN_SECRET)

            val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor(SigningInterceptor(consumer))
                    .build()

            val retrofit = Retrofit.Builder()
                    .baseUrl("https://www.flickr.com/services/rest/")
                    .client(okHttpClient)
                    .addConverterFactory(MoshiConverterFactory.create().asLenient())
                    .build()

            return retrofit.create()
        }

        @Throws(IOException::class)
        internal fun popularPhotos(): List<Photo> {
            // Randomly pick from 4 sources
            val source = (0..3).random()
            val page = (1..500).random()

            val service = createService()
            val oauthService = createOAuthService()

            return when(source.toInt()) {
                0 -> {
                    // Source 1: My favorites
                    service.myFavorites(page).execute()
                }
                1 -> {
                    // Source 2: Random user from following list
                    val contacts = oauthService.getContacts().execute().body()?.contacts?.contact
                        ?: throw IOException("Failed to fetch contacts")
                    if (contacts.isEmpty()) {
                        throw IOException("No contacts found")
                    }
                    val randomUser = contacts.random().nsid
                    service.userPublicPhotos(randomUser, page).execute()
                }
                2 -> {
                    // Source 3: Today's interestingness/explore
                    service.interestingness(page).execute()
                }
                3 -> {
                    // Source 4: Random user from family contacts
                    val familyContacts = oauthService.getFamilyContacts().execute().body()?.contacts?.contact
                        ?: throw IOException("Failed to fetch family contacts")
                    if (familyContacts.isEmpty()) {
                        throw IOException("No family contacts found")
                    }
                    val randomUser = familyContacts.random().nsid
                    service.userPublicPhotos(randomUser, page).execute()
                }
                else -> service.myFavorites(page).execute()
            }.body()?.photos?.photo ?: throw IOException("Response was null")
        }
    }

    @GET("?method=flickr.favorites.getPublicList&user_id=67764677%40N07&format=json&nojsoncallback=1&extras=url_k,url_h,url_l,url_o&per_page=20")
    fun myFavorites(@Query("page") page: Int): Call<Resp>

    @GET("?method=flickr.people.getPublicPhotos&format=json&nojsoncallback=1&extras=url_k,url_h,url_l,url_o&per_page=20")
    fun userPublicPhotos(@Query("user_id") userId: String, @Query("page") page: Int): Call<Resp>

    @GET("?method=flickr.interestingness.getList&format=json&nojsoncallback=1&extras=url_k,url_h,url_l,url_o&per_page=20")
    fun interestingness(@Query("page") page: Int): Call<Resp>

    @GET("?method=flickr.contacts.getList&format=json&nojsoncallback=1&per_page=1000")
    fun getContacts(): Call<ContactsResp>

    @GET("?method=flickr.contacts.getList&format=json&nojsoncallback=1&filter=family&per_page=1000")
    fun getFamilyContacts(): Call<ContactsResp>

    data class Resp(
        val photos: Photos)
    data class Photos(
        val photo: List<Photo>)
    data class Photo(
            val id: String,
            val owner: String,
            val url_k: String?,
            val url_h: String?,
            val url_l: String?,
            val url_o: String?,
            val title: String?) {
        fun getBestUrl(): String? = url_k ?: url_h ?: url_l ?: url_o
    }

    data class ContactsResp(
        val contacts: Contacts)
    data class Contacts(
        val contact: List<Contact>)
    data class Contact(
            val nsid: String,
            val username: String?)
}
