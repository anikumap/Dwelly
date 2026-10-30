package com.example.dwelly

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.example.dwelly.data.SupabaseAuthApi
import com.example.dwelly.data.SupabaseForumApi
import com.example.dwelly.data.SupabaseListingApi
import com.example.dwelly.service.DwellyService
import okhttp3.OkHttpClient

class DwellyApplication : Application() {

    val dwellyService: DwellyService by lazy {
        DwellyService(SupabaseAuthApi(), SupabaseForumApi(), SupabaseListingApi())
    }

    override fun onCreate() {
        super.onCreate()
        // Coil 3 requires explicit setup with the OkHttp network fetcher.
        // Without this call, all http/https URLs silently fail to load.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() }))
                }
                .crossfade(true)
                .build()
        }
    }
}
