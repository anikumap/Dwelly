package com.example.dwelly.service

import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingDetail
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.TenantHome
import com.example.dwelly.model.User

/**
 * Person C. Process 3.0 Discover Listings: tenant feed, filters, sorting,
 * distance, detail and Home. Reads D2; every write goes to [listingService].
 * The tenant's location is on the current user (set by DwellyService.setLocation).
 */
class DiscoveryService(
    private val listingService: ListingService,
    private val currentUser: () -> User?,
) {
    /** LOGIC §4.2. No location -> DwellyError.PromptLocation. */
    suspend fun feed(sort: SortOrder, filter: FeedFilter): Result<List<Listing>> = TODO("Person C")

    /** LOGIC §4.3. */
    suspend fun detail(listingId: String): Result<ListingDetail> = TODO("Person C")

    suspend fun saveListing(listingId: String, saved: Boolean): Result<Unit> = TODO("Person C")

    suspend fun sendEnquiry(listingId: String, message: String): Result<Unit> = TODO("Person C")

    /** LOGIC §4.6. TenantMode is derived here, never stored. */
    suspend fun getHome(): Result<TenantHome> = TODO("Person C")
}
