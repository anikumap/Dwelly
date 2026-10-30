package com.example.dwelly

import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.Listing
import com.example.dwelly.model.Location
import com.example.dwelly.model.PropertyType
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.TenantHome
import com.example.dwelly.model.User
import com.example.dwelly.service.DiscoveryService
import com.example.dwelly.service.ListingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryServiceTest {
    private val discoveryApi = FakeDiscoveryApi()
    private val agreementApi = FakeAgreementApi()
    private val listingApi = FakeListingApi()

    private var currentUser: User? = tenant.copy(location = Location("MG Road", 12.97, 77.59))

    private val listingService = ListingService(listingApi) { currentUser }
    private val discoveryService = DiscoveryService(listingService, discoveryApi, agreementApi) { currentUser }

    private val near = listing("near", rent = 20000.0, lat = 12.971, lng = 77.591, createdAt = "2026-01-01T00:00:00Z")
    private val far = listing("far", rent = 10000.0, lat = 13.10, lng = 77.70, createdAt = "2026-01-02T00:00:00Z")

    private fun listing(id: String, rent: Double, lat: Double, lng: Double, createdAt: String? = null) = Listing(
        listingId = id, landlordId = landlord.userId, title = id, address = "Addr", lat = lat, lng = lng,
        rent = rent, propertyType = PropertyType.APARTMENT, createdAt = createdAt,
    )

    @Test
    fun feedWithoutLocationPromptsForIt() = runBlocking {
        currentUser = tenant.copy(location = null)
        assertTrue(discoveryService.feed(SortOrder.LATEST, FeedFilter()).exceptionOrNull() is DwellyError.PromptLocation)
    }

    @Test
    fun landlordHasNoDiscovery() = runBlocking {
        currentUser = landlord
        assertTrue(discoveryService.feed(SortOrder.LATEST, FeedFilter()).exceptionOrNull() is DwellyError.AccessDenied)
        assertTrue(discoveryService.getHome().exceptionOrNull() is DwellyError.AccessDenied)
    }

    @Test
    fun feedSortsEveryWay() = runBlocking {
        discoveryApi.activeListings.addAll(listOf(near, far))
        fun ids(sort: SortOrder) = runBlocking { discoveryService.feed(sort, FeedFilter()).getOrThrow().map { it.listingId } }

        assertEquals(listOf("far", "near"), ids(SortOrder.LATEST))
        assertEquals(listOf("far", "near"), ids(SortOrder.PRICE_LOW_HIGH))
        assertEquals(listOf("near", "far"), ids(SortOrder.PRICE_HIGH_LOW))
        assertEquals(listOf("near", "far"), ids(SortOrder.NEAREST))
    }

    @Test
    fun feedFiltersByRent() = runBlocking {
        discoveryApi.activeListings.addAll(listOf(near, far))
        val cheap = discoveryService.feed(SortOrder.LATEST, FeedFilter(maxRent = 15000.0)).getOrThrow()
        assertEquals(listOf("far"), cheap.map { it.listingId })
    }

    @Test
    fun haversineDistanceIsAccurate() {
        // MG Road to Koramangala is about 5.1 km.
        assertTrue(DiscoveryService.haversineKm(12.9716, 77.5946, 12.9352, 77.6245) in 5.0..6.0)
    }

    @Test
    fun detailRecordsOneView() = runBlocking {
        discoveryApi.activeListings.add(near)
        assertEquals("near", discoveryService.detail("near").getOrThrow().listing.listingId)
        assertEquals(listOf("recordStat:VIEW:near"), listingApi.calls)
    }

    @Test
    fun unavailableListingIsRefusedAndRecordsNothing() = runBlocking {
        assertTrue(discoveryService.detail("gone").exceptionOrNull() is DwellyError.ListingUnavailable)
        assertTrue(discoveryService.saveListing("gone", true).exceptionOrNull() is DwellyError.ListingUnavailable)
        assertTrue(discoveryService.sendEnquiry("gone", "hi").exceptionOrNull() is DwellyError.ListingUnavailable)
        assertTrue(listingApi.calls.isEmpty())
    }

    @Test
    fun blankEnquiryIsRejected() = runBlocking {
        discoveryApi.activeListings.add(near)
        assertTrue(discoveryService.sendEnquiry("near", "  ").exceptionOrNull() is DwellyError.RejectChange)
        assertTrue(listingApi.calls.isEmpty())
    }

    @Test
    fun homeIsSeekerWithoutAnAgreementAndResidentWithOne() = runBlocking {
        assertTrue(discoveryService.getHome().getOrThrow() is TenantHome.Seeker)

        discoveryApi.activeListings.add(near)
        agreementApi.agreements["ag1"] = Agreement(
            "ag1", "near", landlord.userId, tenant.userId, AgreementStatus.SENT, createdAt = "t",
        )
        val home = discoveryService.getHome().getOrThrow()
        assertTrue(home is TenantHome.Resident)
        assertEquals("near", (home as TenantHome.Resident).property.listing.listingId)
        // Opening Home is not a listing view.
        assertTrue(listingApi.calls.isEmpty())
    }
}
