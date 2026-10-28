package com.agrilens.field.network

import com.agrilens.field.storage.SessionStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiConfig {
    /** Base URL of the deployed Agrilens 2.0 Laravel app's API. */
    const val BASE_URL = "https://agrilens.capstonedemo.com/api/"

    /** Host the in-app result browser is allowed to stay on. */
    const val WEB_HOST = "agrilens.capstonedemo.com"
}

class AuthInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Accept", "application/json")
            .apply {
                sessionStore.token?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()
        return chain.proceed(request)
    }
}

object ApiClient {

    @Volatile
    private var retrofit: Retrofit? = null

    fun create(sessionStore: SessionStore): ApiService {
        return getRetrofit(sessionStore).create(ApiService::class.java)
    }

    private fun getRetrofit(sessionStore: SessionStore): Retrofit {
        return retrofit ?: synchronized(this) {
            retrofit ?: buildRetrofit(sessionStore).also { retrofit = it }
        }
    }

    private fun buildRetrofit(sessionStore: SessionStore): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionStore))
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Colorimeter photos are uploaded over field mobile data.
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
