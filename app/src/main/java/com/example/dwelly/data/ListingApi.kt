package com.example.dwelly.data

import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStats
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.StatMetric
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Process 2.0 Manage Listings. Backed by the SQL functions in 0004_listings.sql. */
interface ListingApi {
    suspend fun createListing(listing: Listing): Listing
    suspend fun updateListing(listing: Listing): Listing
    suspend fun changeListingStatus(listingId: String, status: ListingStatus): Listing
    suspend fun getListing(listingId: String): Listing?
    suspend fun getMyListings(landlordId: String): List<Listing>
    suspend fun getPhotos(listingId: String): List<ListingPhoto>
    suspend fun uploadPhoto(listingId: String, landlordId: String, bytes: ByteArray, position: Int): ListingPhoto
    suspend fun getStats(listingId: String): ListingStats?
    suspend fun recordStat(metric: StatMetric, listingId: String, message: String? = null, saved: Boolean? = null)
}

class SupabaseListingApi(private val client: () -> SupabaseClient = { SupabaseProvider.client }) : ListingApi {

    override suspend fun createListing(listing: Listing): Listing =
        client().postgrest.rpc("create_listing", buildJsonObject {
            put("p_title", listing.title)
            put("p_address", listing.address)
            put("p_lat", listing.lat)
            put("p_lng", listing.lng)
            put("p_rent", listing.rent)
            put("p_property_type", listing.propertyType.name)
            listing.description?.let { put("p_description", it) }
            listing.deposit?.let { put("p_deposit", it) }
            listing.maintenance?.let { put("p_maintenance", it) }
            listing.availableFrom?.let { put("p_available_from", it) }
            listing.leaseMonths?.let { put("p_lease_months", it) }
            listing.bedrooms?.let { put("p_bedrooms", it) }
            listing.bathrooms?.let { put("p_bathrooms", it) }
            listing.balconies?.let { put("p_balconies", it) }
            listing.carpetArea?.let { put("p_carpet_area", it) }
            listing.floorNo?.let { put("p_floor_no", it) }
            listing.furnishing?.let { put("p_furnishing", it.name) }
            put("p_parking_two_wheeler", listing.parkingTwoWheeler)
            put("p_parking_four_wheeler", listing.parkingFourWheeler)
            put("p_amenities", Json.encodeToJsonElement(listing.amenities.map { it.name }))
            put("p_rules", Json.encodeToJsonElement(listing.rules.map { it.name }))
            listing.preferredTenant?.let { put("p_preferred_tenant", it.name) }
        }).decodeAs<Listing>()

    override suspend fun updateListing(listing: Listing): Listing {
        val listingId = requireNotNull(listing.listingId) { "Listing ID is required for update" }
        return client().postgrest.rpc("update_listing", buildJsonObject {
            put("p_listing_id", listingId)
            put("p_title", listing.title)
            put("p_address", listing.address)
            put("p_lat", listing.lat)
            put("p_lng", listing.lng)
            put("p_rent", listing.rent)
            put("p_property_type", listing.propertyType.name)
            listing.description?.let { put("p_description", it) }
            listing.deposit?.let { put("p_deposit", it) }
            listing.maintenance?.let { put("p_maintenance", it) }
            listing.availableFrom?.let { put("p_available_from", it) }
            listing.leaseMonths?.let { put("p_lease_months", it) }
            listing.bedrooms?.let { put("p_bedrooms", it) }
            listing.bathrooms?.let { put("p_bathrooms", it) }
            listing.balconies?.let { put("p_balconies", it) }
            listing.carpetArea?.let { put("p_carpet_area", it) }
            listing.floorNo?.let { put("p_floor_no", it) }
            listing.furnishing?.let { put("p_furnishing", it.name) }
            put("p_parking_two_wheeler", listing.parkingTwoWheeler)
            put("p_parking_four_wheeler", listing.parkingFourWheeler)
            put("p_amenities", Json.encodeToJsonElement(listing.amenities.map { it.name }))
            put("p_rules", Json.encodeToJsonElement(listing.rules.map { it.name }))
            listing.preferredTenant?.let { put("p_preferred_tenant", it.name) }
        }).decodeAs<Listing>()
    }

    override suspend fun changeListingStatus(listingId: String, status: ListingStatus): Listing =
        client().postgrest.rpc("change_listing_status", buildJsonObject {
            put("p_listing_id", listingId)
            put("p_status", status.name)
        }).decodeAs<Listing>()

    override suspend fun getListing(listingId: String): Listing? =
        client().from("listings").select {
            filter { eq("listing_id", listingId) }
        }.decodeSingleOrNull<Listing>()

    override suspend fun getMyListings(landlordId: String): List<Listing> =
        client().from("listings").select {
            filter {
                eq("landlord_id", landlordId)
                neq("status", ListingStatus.DELETED.name)
            }
            order("created_at", Order.DESCENDING)
        }.decodeList<Listing>()

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> =
        client().from("listing_photos").select {
            filter { eq("listing_id", listingId) }
            order("position", Order.ASCENDING)
        }.decodeList<ListingPhoto>()

    override suspend fun uploadPhoto(
        listingId: String,
        landlordId: String,
        bytes: ByteArray,
        position: Int,
    ): ListingPhoto {
        val path = "$landlordId/$listingId/$position.jpg"
        val bucket = client().storage.from("listing-photos")
        bucket.upload(path, bytes) {
            upsert = true
        }
        val url = bucket.publicUrl(path)
        return client().from("listing_photos").insert(
            buildJsonObject {
                put("listing_id", listingId)
                put("url", url)
                put("position", position)
            }
        ) {
            select()
        }.decodeSingle<ListingPhoto>()
    }

    override suspend fun getStats(listingId: String): ListingStats? =
        client().from("listing_stats").select {
            filter { eq("listing_id", listingId) }
        }.decodeSingleOrNull<ListingStats>()

    override suspend fun recordStat(
        metric: StatMetric,
        listingId: String,
        message: String?,
        saved: Boolean?,
    ) {
        client().postgrest.rpc("record_stat", buildJsonObject {
            put("p_listing_id", listingId)
            put("p_metric", metric.name)
            message?.let { put("p_message", it) }
            saved?.let { put("p_saved", it) }
        })
    }
}
