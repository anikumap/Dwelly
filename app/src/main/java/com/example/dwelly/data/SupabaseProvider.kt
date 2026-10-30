package com.example.dwelly.data

import com.example.dwelly.BuildConfig
import com.example.dwelly.model.DwellyError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
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
 * A server failure that isn't one of the spec replies. Its message is safe to show:
 * supabase-kt's own exception messages include the request headers, which carry the
 * user's access token and the API key, so they never reach the screen.
 */
class ServerError(message: String, cause: Throwable? = null) : Exception(message, cause)

const val CONNECTION_ERROR = "Couldn't reach the server. Check your connection and try again."

/**
 * Runs a server call and returns its outcome. A Postgres error whose message is
 * a spec reply becomes the matching [DwellyError]; any other server or network
 * failure becomes a [ServerError] with a short message.
 */
suspend fun <T> serverCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        // PostgREST puts the readable message in error; Auth puts a code there and the message in description.
        val shown = if (e is AuthRestException) e.description ?: e.error else e.error
        Result.failure(DwellyError.fromServerMessage(e.error) ?: ServerError(shown, e))
    } catch (e: HttpRequestException) {
        Result.failure(ServerError(CONNECTION_ERROR, e))
    } catch (e: Exception) {
        Result.failure(e)
    }
