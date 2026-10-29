package com.example.dwelly

import android.app.Application
import com.example.dwelly.data.SupabaseAuthApi
import com.example.dwelly.data.SupabaseForumApi
import com.example.dwelly.data.SupabaseListingApi
import com.example.dwelly.service.DwellyService

class DwellyApplication : Application() {
    val dwellyService: DwellyService by lazy {
        DwellyService(SupabaseAuthApi(), SupabaseForumApi(), SupabaseListingApi())
    }
}
