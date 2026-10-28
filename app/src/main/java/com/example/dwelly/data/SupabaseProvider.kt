package com.example.dwelly.data

import com.example.dwelly.BuildConfig
import com.example.dwelly.model.DwellyError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlin.coroutines.cancellation.CancellationException

object SupabaseProvider {
    /** Created on first use, so a missing configuration surfaces as a call failure, not a crash. */
    val client: SupabaseClient by lazy {
        check(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "Supabase is not configured: set supabase.url and supabase.anonKey in local.properties"
        }
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }
}

/**
 * Runs a server call and returns its outcome. A Postgres error whose message is
 * a spec reply becomes the matching [DwellyError]; anything else stays as is.
 */
suspend fun <T> serverCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        Result.failure(DwellyError.fromServerMessage(e.error) ?: e)
    } catch (e: Exception) {
        Result.failure(e)
    }
