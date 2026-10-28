package com.example.dwelly.service

import com.example.dwelly.model.Completeness
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.User

/**
 * Person B. Process 2.0 Manage Listings: listing rules, ownership, status
 * changes, stats and completeness score. Signatures are the Phase 0 contract
 * and may be refined by the owner.
 */
class ListingService(
    private val forumService: ForumService,
    private val currentUser: () -> User?,
) {
    /** LOGIC §4.1. Must also create the listing's forum thread (forumService.createThread). */
    suspend fun create(listing: Listing, photos: List<ByteArray>): Result<Listing> = TODO("Person B")

    suspend fun update(listing: Listing): Result<Listing> = TODO("Person B")

    /** LOGIC §2 state machine; DELETED is the soft delete. */
    suspend fun changeStatus(listingId: String, status: ListingStatus): Result<Listing> = TODO("Person B")

    suspend fun uploadPhoto(listingId: String, bytes: ByteArray, position: Int): Result<ListingPhoto> = TODO("Person B")

    suspend fun getMyListings(): Result<List<ListingOverview>> = TODO("Person B")

    suspend fun getCompleteness(listingId: String): Result<Completeness> = TODO("Person B")

    /** LOGIC §5. Completeness = filled items / total items x 100. */
    fun score(listing: Listing, photoCount: Int): Completeness = TODO("Person B")

    /** Update request from Discovery (DFD rule: only process 2.0 writes D2). */
    suspend fun recordStat(metric: StatMetric, listingId: String): Result<Unit> = TODO("Person B")
}
