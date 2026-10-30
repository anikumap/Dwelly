package com.example.dwelly.data

import com.example.dwelly.model.Completeness
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStats
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.StatMetric
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

interface ListingApi {
    suspend fun createListing(listing: Listing): Listing
    suspend fun updateListing(listing: Listing): Listing
    suspend fun changeStatus(listingId: String, status: ListingStatus): Listing
    suspend fun uploadPhoto(landlordId: String, listingId: String, bytes: ByteArray, position: Int): ListingPhoto
    suspend fun getMyListings(landlordId: String): List<ListingOverview>
    suspend fun getPhotos(listingId: String): List<ListingPhoto>
    suspend fun getListing(listingId: String): Listing
    suspend fun recordStat(metric: StatMetric, listingId: String, message: String? = null, saved: Boolean = true)
}

class SupabaseListingApi(
    private val client: () -> SupabaseClient = { SupabaseProvider.client },
    private val scoreCalculator: (Listing, Int) -> Completeness,
) : ListingApi {

    override suspend fun createListing(listing: Listing): Listing {
        val json = buildJsonObject {
            put("p_title", listing.title)
            put("p_description", listing.description)
            put("p_address", listing.address)
            put("p_lat", listing.lat)
            put("p_lng", listing.lng)
            put("p_rent", listing.rent)
            put("p_deposit", listing.deposit)
            put("p_maintenance", listing.maintenance)
            put("p_available_from", listing.availableFrom)
            put("p_lease_months", listing.leaseMonths)
            put("p_property_type", listing.propertyType.name)
            put("p_bedrooms", listing.bedrooms)
            put("p_bathrooms", listing.bathrooms)
            put("p_balconies", listing.balconies)
            put("p_carpet_area", listing.carpetArea)
            put("p_floor_no", listing.floorNo)
            put("p_furnishing", listing.furnishing?.name)
            put("p_parking_two_wheeler", listing.parkingTwoWheeler)
            put("p_parking_four_wheeler", listing.parkingFourWheeler)
            put("p_amenities", Json.encodeToJsonElement(listing.amenities.map { it.name }))
            put("p_rules", Json.encodeToJsonElement(listing.rules.map { it.name }))
            put("p_preferred_tenant", listing.preferredTenant?.name)
        }
        return client().postgrest.rpc("create_listing", json).decodeAs()
    }

    override suspend fun updateListing(listing: Listing): Listing {
        val listingId = listing.listingId ?: throw IllegalArgumentException("listing_id required for update")
        val json = buildJsonObject {
            put("p_listing_id", listingId)
            put("p_title", listing.title)
            put("p_description", listing.description)
            put("p_address", listing.address)
            put("p_lat", listing.lat)
            put("p_lng", listing.lng)
            put("p_rent", listing.rent)
            put("p_deposit", listing.deposit)
            put("p_maintenance", listing.maintenance)
            put("p_available_from", listing.availableFrom)
            put("p_lease_months", listing.leaseMonths)
            put("p_property_type", listing.propertyType.name)
            put("p_bedrooms", listing.bedrooms)
            put("p_bathrooms", listing.bathrooms)
            put("p_balconies", listing.balconies)
            put("p_carpet_area", listing.carpetArea)
            put("p_floor_no", listing.floorNo)
            put("p_furnishing", listing.furnishing?.name)
            put("p_parking_two_wheeler", listing.parkingTwoWheeler)
            put("p_parking_four_wheeler", listing.parkingFourWheeler)
            put("p_amenities", Json.encodeToJsonElement(listing.amenities.map { it.name }))
            put("p_rules", Json.encodeToJsonElement(listing.rules.map { it.name }))
            put("p_preferred_tenant", listing.preferredTenant?.name)
        }
        return client().postgrest.rpc("update_listing", json).decodeAs()
    }

    override suspend fun changeStatus(listingId: String, status: ListingStatus): Listing {
        val json = buildJsonObject {
            put("p_listing_id", listingId)
            put("p_status", status.name)
        }
        return client().postgrest.rpc("change_listing_status", json).decodeAs()
    }

    override suspend fun uploadPhoto(
        landlordId: String,
        listingId: String,
        bytes: ByteArray,
        position: Int,
    ): ListingPhoto {
        val path = "$landlordId/$listingId/$position.jpg"
        val storage = client().storage["listing-photos"]
        storage.upload(path, bytes) { upsert = true }
        val publicUrl = storage.publicUrl(path)

        val photoRow = client().from("listing_photos").upsert(
            buildJsonObject {
                put("listing_id", listingId)
                put("url", publicUrl)
                put("position", position)
            },
        ) { select() }.decodeSingle<ListingPhoto>()

        return photoRow
    }

    override suspend fun getMyListings(landlordId: String): List<ListingOverview> {
        val listings = client().from("listings").select {
            filter {
                eq("landlord_id", landlordId)
                neq("status", ListingStatus.DELETED.name)
            }
        }.decodeList<Listing>()

        if (listings.isEmpty()) return emptyList()

        val listingIds = listings.mapNotNull { it.listingId }
        val statsMap = client().from("listing_stats").select {
            filter { isIn("listing_id", listingIds) }
        }.decodeList<ListingStats>().associateBy { it.listingId }

        val photos = client().from("listing_photos").select {
            filter { isIn("listing_id", listingIds) }
        }.decodeList<ListingPhoto>()
        val photoCountMap = photos.groupBy { it.listingId }.mapValues { it.value.size }

        return listings.map { listing ->
            val id = listing.listingId.orEmpty()
            val stats = statsMap[id] ?: ListingStats(id)
            val photoCount = photoCountMap[id] ?: 0
            val completeness = scoreCalculator(listing, photoCount)
            ListingOverview(listing, stats, completeness)
        }
    }

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> =
        client().from("listing_photos").select {
            filter { eq("listing_id", listingId) }
        }.decodeList()

    override suspend fun getListing(listingId: String): Listing =
        client().from("listings").select {
            filter { eq("listing_id", listingId) }
        }.decodeSingle()

    override suspend fun recordStat(
        metric: StatMetric,
        listingId: String,
        message: String?,
        saved: Boolean,
    ) {
        val json = buildJsonObject {
            put("p_listing_id", listingId)
            put("p_metric", metric.name)
            put("p_message", message)
            put("p_saved", saved)
        }
        client().postgrest.rpc("record_stat", json)
    }
}
