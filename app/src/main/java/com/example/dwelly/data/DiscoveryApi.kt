package com.example.dwelly.data

import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Process 3.0 reads of D2. Read-only: every write goes through ListingService (DFD rule).
 * What a tenant can read is decided by the RLS policies in 0004_listings.sql.
 */
interface DiscoveryApi {
    suspend fun getActiveListings(): List<Listing>
    suspend fun getListing(listingId: String): Listing?
    suspend fun getPhotos(listingId: String): List<ListingPhoto>
    suspend fun getPopularListings(): List<Listing>
    suspend fun getRecentlyViewedListings(tenantId: String): List<Listing>
}

class SupabaseDiscoveryApi(
    private val client: () -> SupabaseClient = { SupabaseProvider.client },
) : DiscoveryApi {

    override suspend fun getActiveListings(): List<Listing> =
        client().from("listings").select {
            filter { eq("status", ListingStatus.ACTIVE.name) }
        }.decodeList()

    override suspend fun getListing(listingId: String): Listing? =
        client().from("listings").select {
            filter { eq("listing_id", listingId) }
        }.decodeSingleOrNull()

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> =
        client().from("listing_photos").select {
            filter { eq("listing_id", listingId) }
            order("position", Order.ASCENDING)
        }.decodeList()

    override suspend fun getPopularListings(): List<Listing> =
        client().postgrest.rpc("popular_listings").decodeList()

    /** LOGIC §4.6: ACTIVE only, latest first. */
    override suspend fun getRecentlyViewedListings(tenantId: String): List<Listing> {
        val views = client().from("listing_views").select {
            filter { eq("user_id", tenantId) }
            order("last_viewed_at", Order.DESCENDING)
        }.decodeList<ListingViewRow>()
        if (views.isEmpty()) return emptyList()

        val byId = client().from("listings").select {
            filter {
                isIn("listing_id", views.map { it.listingId })
                eq("status", ListingStatus.ACTIVE.name)
            }
        }.decodeList<Listing>().associateBy { it.listingId }
        return views.mapNotNull { byId[it.listingId] }
    }
}

@Serializable
private data class ListingViewRow(
    @SerialName("listing_id") val listingId: String,
    @SerialName("last_viewed_at") val lastViewedAt: String,
)
