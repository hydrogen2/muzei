package com.example.muzei.unsplash

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import se.akerfeldt.okhttp.signpost.OkHttpOAuthConsumer
import se.akerfeldt.okhttp.signpost.SigningInterceptor

class SearchUsersTest {

    private val CONSUMER_KEY = "fc75f56b6c9dd6476712b93f35aeb2b1"
    private val CONSUMER_SECRET = "27967225db7fce22"
    private val OAUTH_TOKEN = "72157720962156342-cc7f723e08b19c2b"
    private val OAUTH_TOKEN_SECRET = "9d3aaf1845854918"

    interface TestService {
        @GET("?method=flickr.contacts.getList&format=json&nojsoncallback=1&filter=family&per_page=1000")
        fun getFamilyContactsRaw(): Call<ResponseBody>

        @GET("?method=flickr.people.getPublicPhotos&format=json&nojsoncallback=1&extras=url_k&per_page=20")
        fun userPublicPhotos(@Query("user_id") userId: String, @Query("page") page: Int): Call<PhotosResp>

        @GET("?method=flickr.people.getInfo&format=json&nojsoncallback=1")
        fun getUserInfo(@Query("user_id") userId: String): Call<ResponseBody>

        data class PhotosResp(val photos: Photos)
        data class Photos(val photo: List<Photo>, val pages: Int?, val total: String?)
        data class Photo(val id: String, val owner: String, val url_k: String?, val title: String?)
    }

    private fun createOAuthService(): TestService {
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

        return retrofit.create(TestService::class.java)
    }

    private fun createService(): TestService {
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

        return retrofit.create(TestService::class.java)
    }

    @Test
    fun testGetFamilyContacts() {
        val oauthService = createOAuthService()
        val service = createService()

        println("=== Testing getContacts with filter=family ===")
        val response = oauthService.getFamilyContactsRaw().execute()
        val body = response.body()?.string()
        println("Family contacts: $body")

        // Parse and test each family contact's photos
        // Hardcoded NSIDs from earlier test
        val familyNsids = listOf(
            "36382872@N03",  // China Online Museum
            "28921941@N06",  // mi16885
            "74399113@N07",  // Patrick Vierthaler
            "193422203@N08", // weizhiwei2016
            "138212178@N06"  // yurenty
        )

        println("\n=== Testing userPublicPhotos for each family contact ===")
        for (nsid in familyNsids) {
            val page = 1
            val photosResponse = service.userPublicPhotos(nsid, page).execute()
            val photosBody = photosResponse.body()
            println("\nUser $nsid page $page:")
            println("  Response successful: ${photosResponse.isSuccessful}")
            if (photosBody != null) {
                val photos = photosBody.photos.photo
                println("  Total pages: ${photosBody.photos.pages}, Total photos: ${photosBody.photos.total}")
                println("  Photos returned: ${photos.size}")
                println("  Photos with url_k: ${photos.count { it.url_k != null }}")
                photos.take(3).forEach { photo ->
                    println("    - id=${photo.id}, url_k=${photo.url_k?.take(50)}...")
                }
            }
        }

        // Test with high page number like the actual code does
        println("\n=== Testing with high page number (like actual code) ===")
        val randomPage = (1..500).random()
        println("Random page: $randomPage")
        for (nsid in familyNsids.take(2)) {
            val photosResponse = service.userPublicPhotos(nsid, randomPage).execute()
            val photosBody = photosResponse.body()
            println("User $nsid page $randomPage: ${photosBody?.photos?.photo?.size ?: 0} photos")
        }
    }
}
