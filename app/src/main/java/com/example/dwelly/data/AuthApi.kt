package com.example.dwelly.data

import com.example.dwelly.model.Admin
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Landlord
import com.example.dwelly.model.Location
import com.example.dwelly.model.Role
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.User
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process 1.0 Authenticate & Authorize, and the D1 users store. */
interface AuthApi {
    /** Returns true when the new account is signed in right away (email confirmation off). */
    suspend fun signUp(name: String, email: String, phone: String?, password: String, role: Role): Boolean

    suspend fun signIn(email: String, password: String)

    suspend fun signOut()

    /** Id of the signed-in user from a stored session, or null. */
    suspend fun currentUserId(): String?

    suspend fun fetchUser(userId: String): User

    suspend fun updateLocation(userId: String, location: Location)
}

class SupabaseAuthApi(private val client: () -> SupabaseClient = { SupabaseProvider.client }) : AuthApi {

    override suspend fun signUp(name: String, email: String, phone: String?, password: String, role: Role): Boolean {
        val auth = client().auth
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
            // Read by the on_auth_user_created trigger (0002_auth.sql).
            data = buildJsonObject {
                put("name", name)
                put("phone", phone)
                put("role", role.name)
            }
        }
        return auth.currentSessionOrNull() != null
    }

    override suspend fun signIn(email: String, password: String) {
        try {
            client().auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (e: AuthRestException) {
            throw DwellyError.AccessDenied(e.description ?: e.error)
        }
    }

    override suspend fun signOut() {
        client().auth.signOut()
    }

    override suspend fun currentUserId(): String? {
        val auth = client().auth
        auth.awaitInitialization()
        return auth.currentUserOrNull()?.id
    }

    override suspend fun fetchUser(userId: String): User =
        client().from("users").select {
            filter { eq("user_id", userId) }
        }.decodeSingle<UserRow>().toUser()

    override suspend fun updateLocation(userId: String, location: Location) {
        client().from("users").update(LocationRow(location.label, location.lat, location.lng)) {
            filter { eq("user_id", userId) }
        }
    }
}

@Serializable
private data class UserRow(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val role: Role,
    @SerialName("location_label") val locationLabel: String? = null,
    @SerialName("location_lat") val locationLat: Double? = null,
    @SerialName("location_lng") val locationLng: Double? = null,
) {
    fun toUser(): User = when (role) {
        Role.LANDLORD -> Landlord(userId, name, email, phone)
        Role.ADMIN -> Admin(userId, name, email, phone)
        Role.TENANT -> Tenant(
            userId, name, email, phone,
            location = if (locationLabel != null && locationLat != null && locationLng != null) {
                Location(locationLabel, locationLat, locationLng)
            } else {
                null
            },
        )
    }
}

@Serializable
private data class LocationRow(
    @SerialName("location_label") val label: String,
    @SerialName("location_lat") val lat: Double,
    @SerialName("location_lng") val lng: Double,
)
