package com.example.dwelly

import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.Listing
import com.example.dwelly.model.Location
import com.example.dwelly.model.PropertyType
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.TenantHome
import com.example.dwelly.service.DiscoveryService
import com.example.dwelly.service.ForumService
import com.example.dwelly.service.ListingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryServiceTest {
    private val auth = FakeAuthApi().apply {
        add(tenant, "pw")
        add(landlord, "pw")
    }
    private val discoveryApi = FakeDiscoveryApi()
    private val agreementApi = FakeAgreementApi()
    private val listingApi = FakeListingApi()
    private val forumApi = FakeForumApi()

    private var currentUser: Tenant? = tenant

    private val forumService = ForumService(forumApi) { currentUser }
    private val listingService = ListingService(forumService, listingApi) { currentUser }
    private val discoveryService = DiscoveryService(listingService, discoveryApi, agreementApi) { currentUser }

    @Test
    fun feedWithoutLocationReturnsPromptLocation() = runBlocking {
        currentUser = tenant.copy(location = null)
        val result = discoveryService.feed(SortOrder.LATEST, FeedFilter())
        assertTrue(result.exceptionOrNull() is DwellyError.PromptLocation)
    }

    @Test
    fun feedWithLocationFiltersAndSorts() = runBlocking {
        currentUser = tenant.copy(location = Location("MG Road", 12.97, 77.59))

        val l1 = Listing(listingId = "l1", landlordId = "l1", title = "Apt 1", address = "Loc 1", lat = 12.97, lng = 77.59, rent = 10000.0, propertyType = PropertyType.APARTMENT, createdAt = "2025-01-01T00:00:00Z")
        val l2 = Listing(listingId = "l2", landlordId = "l1", title = "Apt 2", address = "Loc 2", lat = 12.98, lng = 77.60, rent = 20000.0, propertyType = PropertyType.APARTMENT, createdAt = "2025-01-02T00:00:00Z")
        discoveryApi.activeListings.addAll(listOf(l1, l2))

        val lowToHigh = discoveryService.feed(SortOrder.PRICE_LOW_HIGH, FeedFilter()).getOrThrow()
        assertEquals(listOf(l1, l2), lowToHigh)

        val highToLow = discoveryService.feed(SortOrder.PRICE_HIGH_LOW, FeedFilter()).getOrThrow()
        assertEquals(listOf(l2, l1), highToLow)
    }

    @Test
    fun haversineDistanceCalculationIsAccurate() {
        val distKm = DiscoveryService.haversineKm(12.9716, 77.5946, 12.9352, 77.6245)
        assertTrue(distKm in 5.0..6.0)
    }

    @Test
    fun getHomeDerivesSeekerAndResidentModes() = runBlocking {
        currentUser = tenant.copy(location = Location("MG Road", 12.97, 77.59))
        val homeSeeker = discoveryService.getHome().getOrThrow()
        assertTrue(homeSeeker is TenantHome.Seeker)

        val l1 = Listing(listingId = "l1", landlordId = "l1", title = "Apt 1", address = "Loc 1", lat = 12.97, lng = 77.59, rent = 10000.0, propertyType = PropertyType.APARTMENT)
        discoveryApi.activeListings.add(l1)
        agreementApi.agreements["ag1"] = Agreement("ag1", "l1", "l1", tenant.userId, AgreementStatus.SENT, mapOf("terms" to "yes"), createdAt = "t")

        val homeResident = discoveryService.getHome().getOrThrow()
        assertTrue(homeResident is TenantHome.Resident)
    }
}
