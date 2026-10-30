package com.example.dwelly.service

import com.example.dwelly.data.ListingApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.Completeness
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.Role
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.User

/**
 * Person B. Process 2.0 Manage Listings: listing rules, ownership, status
 * changes, stats and completeness score.
 */
class ListingService(
    private val forumService: ForumService,
    private val listingApi: ListingApi? = null,
    private val currentUser: () -> User?,
) {
    private fun getApi(): ListingApi = listingApi ?: throw IllegalStateException("ListingApi not provided")

    /** LOGIC §4.1. Must also create the listing's forum thread (forumService.createThread). */
    suspend fun create(listing: Listing, photos: List<ByteArray>): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())

        return serverCall {
            val created = getApi().createListing(listing.copy(landlordId = user.userId))
            val listingId = created.listingId ?: throw IllegalStateException("Created listing has no ID")

            photos.forEachIndexed { pos, bytes ->
                getApi().uploadPhoto(user.userId, listingId, bytes, pos)
            }
            created
        }
    }

    suspend fun update(listing: Listing): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())
        if (listing.landlordId != user.userId) return Result.failure(DwellyError.AccessDenied())
        if (listing.status == ListingStatus.DELETED) {
            return Result.failure(DwellyError.RejectChange("Cannot edit a DELETED listing"))
        }

        return serverCall { getApi().updateListing(listing) }
    }

    /** LOGIC §2 state machine; DELETED is the soft delete. */
    suspend fun changeStatus(listingId: String, status: ListingStatus): Result<Listing> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())

        return serverCall { getApi().changeStatus(listingId, status) }
    }

    suspend fun uploadPhoto(listingId: String, bytes: ByteArray, position: Int): Result<ListingPhoto> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())

        return serverCall { getApi().uploadPhoto(user.userId, listingId, bytes, position) }
    }

    suspend fun getMyListings(): Result<List<ListingOverview>> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())

        return serverCall { getApi().getMyListings(user.userId) }
    }

    suspend fun getCompleteness(listingId: String): Result<Completeness> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())

        return serverCall {
            val listing = getApi().getListing(listingId)
            if (listing.landlordId != user.userId) throw DwellyError.AccessDenied()
            val photos = getApi().getPhotos(listingId)
            score(listing, photos.size)
        }
    }

    /** LOGIC §5. Completeness = filled items / total items x 100. */
    fun score(listing: Listing, photoCount: Int): Completeness {
        val suggestions = mutableListOf<String>()
        var filled = 0

        // 1. Description
        if (!listing.description.isNullWithBlank()) {
            filled++
        } else {
            suggestions += "Add a description for your listing"
        }

        // 2. Photos
        if (photoCount > 0) {
            filled++
        } else {
            suggestions += "Upload photos to attract more tenants"
        }

        // 3. Property details
        if (listing.bedrooms != null && listing.bathrooms != null && listing.carpetArea != null) {
            filled++
        } else {
            suggestions += "Provide property details (bedrooms, bathrooms, carpet area)"
        }

        // 4. Price fields
        if (listing.rent > 0 && listing.deposit != null && listing.maintenance != null) {
            filled++
        } else {
            suggestions += "Specify deposit and maintenance amounts"
        }

        // 5. Amenities
        if (listing.amenities.isNotEmpty()) {
            filled++
        } else {
            suggestions += "Add available amenities"
        }

        // 6. Rules
        if (listing.rules.isNotEmpty()) {
            filled++
        } else {
            suggestions += "Add house rules"
        }

        val scoreValue = (filled * 100) / 6
        return Completeness(scoreValue, suggestions)
    }

    /** Update request from Discovery (DFD rule: only process 2.0 writes D2). */
    suspend fun recordStat(metric: StatMetric, listingId: String): Result<Unit> =
        serverCall { getApi().recordStat(metric, listingId) }

    suspend fun recordSaveStat(listingId: String, saved: Boolean): Result<Unit> =
        serverCall { getApi().recordStat(StatMetric.SAVE, listingId, saved = saved) }

    suspend fun recordEnquiryStat(listingId: String, message: String): Result<Unit> =
        serverCall { getApi().recordStat(StatMetric.ENQUIRY, listingId, message = message) }

    private fun String?.isNullWithBlank(): Boolean = this == null || this.trim().isEmpty()
}
