package com.example.moniq.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

object RetrofitClient {
    private var retrofit: Retrofit? = null
    var api: OpenSubsonicApi? = null
        private set
    
    fun initialize(baseUrl: String) {
        val logging = HttpLoggingInterceptor()
        logging.level = HttpLoggingInterceptor.Level.BODY
        
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
        
        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
        
        api = retrofit?.create(OpenSubsonicApi::class.java)
    }
    
    // Keep the old method for backward compatibility
    fun create(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor()
        logging.level = HttpLoggingInterceptor.Level.BODY
        
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }
}