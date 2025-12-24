package com.example.moniq.network

import retrofit2.Response
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenSubsonicApi {
    @GET("rest/ping.view")
    suspend fun ping(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("id") albumId: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("type") type: String,
        @Query("artistId") artistId: String? = null,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("id") artistId: String,
        @Query("f") format: String = "json",
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/getArtistInfo.view")
    suspend fun getArtistInfo(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("id") artistId: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/search3.view")
    suspend fun search3(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 10,
        @Query("albumCount") albumCount: Int = 10,
        @Query("songCount") songCount: Int = 25,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/getSong.view")
    suspend fun getSong(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("id") id: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<String>

    @GET("rest/stream.view")
    suspend fun stream(
        @Query("u") username: String,
        @Query("p") password: String,
        @Query("id") id: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") clientName: String = "Moniq"
    ): Response<ResponseBody>
}