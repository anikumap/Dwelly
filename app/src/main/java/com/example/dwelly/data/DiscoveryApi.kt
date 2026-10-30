package com.example.dwelly.data

import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

interface DiscoveryApi {
    suspend fun getActiveListings(): List<Listing>
    suspend fun getListing(listingId: String): Listing?
    suspend fun getPhotos(listingId: String): List<ListingPhoto>
    suspend fun getPopularListings(): List<Listing>
    suspend fun getRecentlyViewedListings(tenantId: String): List<Listing>
    suspend fun getSavedListingIds(tenantId: String): List<String>
}

class SupabaseDiscoveryApi(
    private val client: () -> SupabaseClient = { SupabaseProvider.client },
) : DiscoveryApi {

    override suspend fun getActiveListings(): List<Listing> =
        client().from("listings").select {
            filter { eq("status", ListingStatus.ACTIVE.name) }
        }.decodeList()

    override suspend fun getListing(listingId: String): Listing? =
        try {
            client().from("listings").select {
                filter { eq("listing_id", listingId) }
            }.decodeSingle<Listing>()
        } catch (_: Exception) {
            null
        }

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> =
        client().from("listing_photos").select {
            filter { eq("listing_id", listingId) }
        }.decodeList()

    override suspend fun getPopularListings(): List<Listing> =
        try {
            client().postgrest.rpc("popular_listings", buildJsonObject { }).decodeList()
        } catch (_: Exception) {
            // Fallback to active listings if 0005_agreements.sql RPC hasn't been executed on Supabase
            getActiveListings()
        }

    override suspend fun getRecentlyViewedListings(tenantId: String): List<Listing> {
        val views = try {
            client().from("listing_views").select {
                filter { eq("user_id", tenantId) }
            }.decodeList<ListingViewRow>()
        } catch (_: Exception) {
            emptyList()
        }

        if (views.isEmpty()) return emptyList()

        val listingIds = views.sortedByDescending { it.lastViewedAt }.map { it.listingId }
        val listings = client().from("listings").select {
            filter {
                isIn("listing_id", listingIds)
                eq("status", ListingStatus.ACTIVE.name)
            }
        }.decodeList<Listing>()

        val listingMap = listings.associateBy { it.listingId }
        return listingIds.mapNotNull { listingMap[it] }
    }

    override suspend fun getSavedListingIds(tenantId: String): List<String> =
        try {
            client().from("listing_saves").select {
                filter { eq("user_id", tenantId) }
            }.decodeList<ListingSaveRow>().map { it.listingId }
        } catch (_: Exception) {
            emptyList()
        }
}

@Serializable
private data class ListingViewRow(
    @SerialName("user_id") val userId: String,
    @SerialName("listing_id") val listingId: String,
    @SerialName("last_viewed_at") val lastViewedAt: String,
)

@Serializable
private data class ListingSaveRow(
    @SerialName("user_id") val userId: String,
    @SerialName("listing_id") val listingId: String,
)
