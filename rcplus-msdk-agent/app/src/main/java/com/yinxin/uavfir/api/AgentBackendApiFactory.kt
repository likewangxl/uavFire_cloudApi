package com.yinxin.uavfir.api

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object AgentBackendApiFactory {
    fun create(config: AgentBackendConfig = AgentBackendConfig()): DualStreamApi {
        return retrofit(config).create(DualStreamApi::class.java)
    }

    fun <T : Any> create(api: Class<T>, config: AgentBackendConfig = AgentBackendConfig()): T {
        return retrofit(config).create(api)
    }

    private fun retrofit(config: AgentBackendConfig): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(backendGson()))
            .build()
    }
}

internal fun backendGson(): Gson = GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .create()
