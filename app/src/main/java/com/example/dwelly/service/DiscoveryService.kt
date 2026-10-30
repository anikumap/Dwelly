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
    private val discoveryApi: DiscoveryApi,
    private val agreementApi: AgreementApi,
    private val currentUser: () -> User?,
) {
    /** LOGIC §4.2. No location -> DwellyError.PromptLocation. */
    suspend fun feed(sort: SortOrder, filter: FeedFilter): Result<List<Listing>> {
        val tenant = currentTenant() ?: return Result.failure(DwellyError.AccessDenied())
        val loc = tenant.location ?: return Result.failure(DwellyError.PromptLocation())

        return serverCall {
            val filtered = discoveryApi.getActiveListings().filter { listing ->
                (filter.minRent == null || listing.rent >= filter.minRent) &&
                    (filter.maxRent == null || listing.rent <= filter.maxRent) &&
                    (filter.propertyType == null || listing.propertyType == filter.propertyType) &&
                    (filter.furnishing == null || listing.furnishing == filter.furnishing) &&
                    (filter.bedrooms == null || listing.bedrooms == filter.bedrooms)
            }

            when (sort) {
                SortOrder.LATEST -> filtered.sortedByDescending { it.createdAt.orEmpty() }
                SortOrder.PRICE_LOW_HIGH -> filtered.sortedBy { it.rent }
                SortOrder.PRICE_HIGH_LOW -> filtered.sortedByDescending { it.rent }
                SortOrder.NEAREST -> filtered.sortedBy { haversineKm(loc.lat, loc.lng, it.lat, it.lng) }
            }
        }
    }

    /** LOGIC §4.3, messages 13–18: check visibility first, then record the view (D5). */
    suspend fun detail(listingId: String): Result<ListingDetail> {
        if (currentTenant() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            val listing = visibleListing(listingId)
            listingService.recordStat(StatMetric.VIEW, listingId).getOrThrow()
            ListingDetail(listing, discoveryApi.getPhotos(listingId))
        }
    }

    suspend fun saveListing(listingId: String, saved: Boolean): Result<Unit> {
        if (currentTenant() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            visibleListing(listingId)
            listingService.recordStat(StatMetric.SAVE, listingId, saved = saved).getOrThrow()
        }
    }

    suspend fun sendEnquiry(listingId: String, message: String): Result<Unit> {
        if (currentTenant() == null) return Result.failure(DwellyError.AccessDenied())
        if (message.isBlank()) return Result.failure(DwellyError.RejectChange("message is required for enquiry"))
        return serverCall {
            visibleListing(listingId)
            listingService.recordStat(StatMetric.ENQUIRY, listingId, message = message.trim()).getOrThrow()
        }
    }

    /** LOGIC §4.6. TenantMode is derived here, never stored (plan.md D2). */
    suspend fun getHome(): Result<TenantHome> {
        val tenant = currentTenant() ?: return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            val agreement = agreementApi.getTenantAgreement(tenant.userId)
            if (agreement != null) {
                // Opening Home isn't viewing a listing, so this doesn't record a VIEW.
                val property = visibleListing(agreement.listingId)
                TenantHome.Resident(ListingDetail(property, discoveryApi.getPhotos(agreement.listingId)))
            } else {
                TenantHome.Seeker(
                    recentlyViewed = discoveryApi.getRecentlyViewedListings(tenant.userId),
                    popular = discoveryApi.getPopularListings(),
                )
            }
        }
    }

    private fun currentTenant(): Tenant? = currentUser()?.takeIf { it.role == Role.TENANT } as? Tenant

    /**
     * LOGIC §4.3 step 14: ACTIVE, or the RESIDENT's own rented property. The listings RLS
     * policy returns exactly those rows to a tenant, so an unreadable listing is unavailable.
     */
    private suspend fun visibleListing(listingId: String): Listing =
        discoveryApi.getListing(listingId) ?: throw DwellyError.ListingUnavailable()

    companion object {
        fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
