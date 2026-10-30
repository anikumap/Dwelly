package com.example.dwelly.service

import com.example.dwelly.data.AgreementApi
import com.example.dwelly.data.DiscoveryApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingDetail
import com.example.dwelly.model.Role
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.TenantHome
import com.example.dwelly.model.User
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Person C. Process 3.0 Discover Listings: tenant feed, filters, sorting,
 * distance, detail and Home. Reads D2; every write goes to [listingService].
 * The tenant's location is on the current user (set by DwellyService.setLocation).
 */
class DiscoveryService(
    private val listingService: ListingService,
    private val discoveryApi: DiscoveryApi? = null,
    private val agreementApi: AgreementApi? = null,
    private val currentUser: () -> User?,
) {
    private fun getDiscoveryApi(): DiscoveryApi = discoveryApi ?: throw IllegalStateException("DiscoveryApi not provided")
    private fun getAgreementApi(): AgreementApi = agreementApi ?: throw IllegalStateException("AgreementApi not provided")

    /** LOGIC §4.2. No location -> DwellyError.PromptLocation. */
    suspend fun feed(sort: SortOrder, filter: FeedFilter): Result<List<Listing>> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())

        val tenant = user as Tenant
        val loc = tenant.location ?: return Result.failure(DwellyError.PromptLocation())

        return serverCall {
            val listings = getDiscoveryApi().getActiveListings()

            val filtered = listings.filter { listing ->
                if ((filter.minRent != null) && (listing.rent < filter.minRent)) return@filter false
                if ((filter.maxRent != null) && (listing.rent > filter.maxRent)) return@filter false
                if ((filter.propertyType != null) && (listing.propertyType != filter.propertyType)) return@filter false
                if ((filter.furnishing != null) && (listing.furnishing != filter.furnishing)) return@filter false
                if ((filter.bedrooms != null) && (listing.bedrooms != filter.bedrooms)) return@filter false
                true
            }

            when (sort) {
                SortOrder.LATEST -> filtered.sortedByDescending { it.createdAt.orEmpty() }
                SortOrder.PRICE_LOW_HIGH -> filtered.sortedBy { it.rent }
                SortOrder.PRICE_HIGH_LOW -> filtered.sortedByDescending { it.rent }
                SortOrder.NEAREST -> filtered.sortedBy { listing ->
                    haversineKm(loc.lat, loc.lng, listing.lat, listing.lng)
                }
            }
        }
    }

    /** LOGIC §4.3. */
    suspend fun detail(listingId: String): Result<ListingDetail> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())

        return serverCall {
            val photos = getDiscoveryApi().getPhotos(listingId)
            val listing = checkVisibleListing(listingId, user.userId)
            listingService.recordStat(StatMetric.VIEW, listingId)
            ListingDetail(listing, photos)
        }
    }

    suspend fun saveListing(listingId: String, saved: Boolean): Result<Unit> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())

        return serverCall {
            checkVisibleListing(listingId, user.userId)
            listingService.recordSaveStat(listingId, saved)
            Unit
        }
    }

    suspend fun sendEnquiry(listingId: String, message: String): Result<Unit> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())
        if (message.trim().isEmpty()) {
            return Result.failure(DwellyError.RejectChange("Message cannot be empty"))
        }

        return serverCall {
            checkVisibleListing(listingId, user.userId)
            listingService.recordEnquiryStat(listingId, message)
            Unit
        }
    }

    /** LOGIC §4.6. TenantMode is derived here, never stored. */
    suspend fun getHome(): Result<TenantHome> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())

        return serverCall {
            val activeAgreement = getAgreementApi().getTenantAgreement(user.userId)
            if (activeAgreement != null) {
                val detailResult = detail(activeAgreement.listingId).getOrThrow()
                TenantHome.Resident(detailResult)
            } else {
                val recentlyViewed = getDiscoveryApi().getRecentlyViewedListings(user.userId)
                val popular = getDiscoveryApi().getPopularListings()
                TenantHome.Seeker(recentlyViewed, popular)
            }
        }
    }

    private suspend fun checkVisibleListing(listingId: String, tenantId: String): Listing {
        val activeAgreement = getAgreementApi().getTenantAgreement(tenantId)
        val isOwnRentedProperty = activeAgreement?.listingId == listingId

        val foundActive = getDiscoveryApi().getActiveListings().firstOrNull { it.listingId == listingId }
        if (foundActive != null) return foundActive

        if (isOwnRentedProperty) {
            return getDiscoveryApi().getListing(listingId) ?: throw DwellyError.ListingUnavailable()
        }

        throw DwellyError.ListingUnavailable()
    }

    companion object {
        fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
