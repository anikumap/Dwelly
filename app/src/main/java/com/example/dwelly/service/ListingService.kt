package com.example.dwelly.service

import com.example.dwelly.data.ListingApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.Completeness
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStats
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.Role
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.User

/**
 * Person B. Process 2.0 Manage Listings: listing rules, ownership, status
 * changes, stats and completeness score.
 */
class ListingService(
    private val listingApi: ListingApi,
    private val forumService: ForumService,
    private val currentUser: () -> User?,
) {
    /** LOGIC §4.1. Forum thread is created in the create_listing transaction. */
    suspend fun create(
        listing: Listing,
        photos: List<ByteArray> = emptyList(),
        photoUrls: List<String> = emptyList(),
    ): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) {
            return Result.failure(DwellyError.AccessDenied("Only landlords can create listings"))
        }

        return serverCall {
            val created = listingApi.createListing(listing.copy(landlordId = user.userId))
            val listingId = requireNotNull(created.listingId) { "Listing created without id" }
            forumService.createThread(listingId)
            var currentPos = 0
            if (photos.isNotEmpty()) {
                photos.forEach { bytes ->
                    listingApi.uploadPhoto(listingId, user.userId, bytes, currentPos++)
                }
            }
            if (photoUrls.isNotEmpty()) {
                photoUrls.forEach { url ->
                    listingApi.addPhotoUrl(listingId, url, currentPos++)
                }
            }
            created
        }
    }

    suspend fun update(listing: Listing): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD || listing.landlordId != user.userId) {
            return Result.failure(DwellyError.AccessDenied())
        }
        if (listing.status == ListingStatus.DELETED) {
            return Result.failure(DwellyError.RejectChange("listing is DELETED"))
        }
        return serverCall {
            listingApi.updateListing(listing)
        }
    }

    /** LOGIC §2 state machine; DELETED is the soft delete. */
    suspend fun changeStatus(listingId: String, status: ListingStatus): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) {
            return Result.failure(DwellyError.AccessDenied())
        }
        return serverCall {
            listingApi.changeListingStatus(listingId, status)
        }
    }

    suspend fun uploadPhoto(listingId: String, bytes: ByteArray, position: Int): Result<ListingPhoto> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) {
            return Result.failure(DwellyError.AccessDenied())
        }
        return serverCall {
            listingApi.uploadPhoto(listingId, user.userId, bytes, position)
        }
    }

    suspend fun addPhotoUrl(listingId: String, url: String, position: Int): Result<ListingPhoto> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) {
            return Result.failure(DwellyError.AccessDenied())
        }
        return serverCall {
            listingApi.addPhotoUrl(listingId, url, position)
        }
    }

    suspend fun getMyListings(): Result<List<ListingOverview>> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) {
            return Result.failure(DwellyError.AccessDenied())
        }
        return serverCall {
            val listings = listingApi.getMyListings(user.userId)
            listings.filter { it.status != ListingStatus.DELETED }.map { listing ->
                val listingId = listing.listingId.orEmpty()
                val photos = if (listingId.isNotEmpty()) listingApi.getPhotos(listingId) else emptyList()
                val stats = if (listingId.isNotEmpty()) listingApi.getStats(listingId) ?: ListingStats(listingId) else ListingStats("")
                val completeness = score(listing, photos.size)
                ListingOverview(listing, stats, completeness, photos)
            }
        }
    }

    suspend fun getCompleteness(listingId: String): Result<Completeness> = serverCall {
        val listing = listingApi.getListing(listingId) ?: throw DwellyError.ListingUnavailable()
        val photos = listingApi.getPhotos(listingId)
        score(listing, photos.size)
    }

    /** LOGIC §5. Completeness = filled items / total items x 100. */
    fun score(listing: Listing, photoCount: Int): Completeness {
        val suggestions = mutableListOf<String>()
        var filled = 0

        // 1. Description
        if (!listing.description.isNullOrBlank()) {
            filled++
        } else {
            suggestions.add("Add a description to attract more tenants")
        }

        // 2. Photos
        if (photoCount > 0) {
            filled++
        } else {
            suggestions.add("Add at least one photo of the property")
        }

        // 3. Property details: bedrooms, bathrooms, carpetArea, floorNo, furnishing
        val hasDetails = listing.bedrooms != null && listing.bathrooms != null &&
            listing.carpetArea != null && listing.furnishing != null
        if (hasDetails) {
            filled++
        } else {
            suggestions.add("Fill in property details (bedrooms, bathrooms, area, furnishing)")
        }

        // 4. Price fields: deposit, maintenance, leaseMonths
        val hasPriceFields = listing.deposit != null && listing.maintenance != null && listing.leaseMonths != null
        if (hasPriceFields) {
            filled++
        } else {
            suggestions.add("Add deposit, maintenance and lease duration")
        }

        // 5. Amenities
        if (listing.amenities.isNotEmpty()) {
            filled++
        } else {
            suggestions.add("Select available amenities")
        }

        // 6. Rules
        if (listing.rules.isNotEmpty()) {
            filled++
        } else {
            suggestions.add("Specify house rules and preferences")
        }

        val score = (filled * 100) / 6
        return Completeness(score, suggestions)
    }

    /** Update request from Discovery (DFD rule: only process 2.0 writes D2). */
    suspend fun recordStat(
        metric: StatMetric,
        listingId: String,
        message: String? = null,
        saved: Boolean? = null,
    ): Result<Unit> = serverCall {
        listingApi.recordStat(metric, listingId, message, saved)
    }
}
