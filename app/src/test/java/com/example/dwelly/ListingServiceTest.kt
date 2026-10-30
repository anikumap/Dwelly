package com.example.dwelly

import com.example.dwelly.model.Amenity
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Furnishing
import com.example.dwelly.model.HouseRule
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.PropertyType
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.User
import com.example.dwelly.service.ListingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListingServiceTest {

    private lateinit var listingApi: FakeListingApi
    private var currentUser: User? = null
    private lateinit var listingService: ListingService

    private val sampleListing = Listing(
        listingId = "l-100",
        landlordId = landlord.userId,
        title = "Modern 2BHK",
        description = "A wonderful bright apartment",
        address = "Indiranagar, Bangalore",
        lat = 12.9784,
        lng = 77.6408,
        rent = 30000.0,
        deposit = 60000.0,
        maintenance = 2000.0,
        availableFrom = "2026-11-01",
        leaseMonths = 11,
        propertyType = PropertyType.APARTMENT,
        bedrooms = 2,
        bathrooms = 2,
        balconies = 1,
        carpetArea = 950.0,
        floorNo = 3,
        furnishing = Furnishing.SEMI_FURNISHED,
        parkingTwoWheeler = 1,
        parkingFourWheeler = 1,
        amenities = listOf(Amenity.WIFI, Amenity.LIFT),
        rules = listOf(HouseRule.NO_SMOKING),
        status = ListingStatus.ACTIVE,
    )

    @Before
    fun setup() {
        listingApi = FakeListingApi()
        listingService = ListingService(listingApi) { currentUser }
    }

    @Test
    fun `landlord can create listing`() = runBlocking {
        currentUser = landlord
        val result = listingService.create(sampleListing)
        assertTrue(result.isSuccess)
        val created = result.getOrThrow()
        assertEquals(ListingStatus.ACTIVE, created.status)
        assertEquals(landlord.userId, created.landlordId)
    }

    @Test
    fun `tenant cannot create listing`() = runBlocking {
        currentUser = tenant
        val result = listingService.create(sampleListing)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DwellyError.AccessDenied)
    }

    @Test
    fun `landlord can update own listing`() = runBlocking {
        currentUser = landlord
        listingApi.createListing(sampleListing)

        val updated = sampleListing.copy(rent = 32000.0)
        val result = listingService.update(updated)
        assertTrue(result.isSuccess)
        assertEquals(32000.0, result.getOrThrow().rent, 0.01)
    }

    @Test
    fun `cannot update deleted listing`() = runBlocking {
        currentUser = landlord
        val deletedListing = sampleListing.copy(status = ListingStatus.DELETED)
        listingApi.listings[deletedListing.listingId!!] = deletedListing

        val result = listingService.update(deletedListing)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DwellyError.RejectChange)
    }

    @Test
    fun `photo upload failure fails the create instead of being swallowed`() = runBlocking {
        currentUser = landlord
        listingApi.failPhotoUpload = true
        val result = listingService.create(sampleListing, listOf(byteArrayOf(1, 2, 3)))
        assertTrue(result.isFailure)
    }

    @Test
    fun `completeness is only for the owning landlord`() = runBlocking {
        listingApi.createListing(sampleListing)
        currentUser = tenant
        assertTrue(listingService.getCompleteness("l-100").exceptionOrNull() is DwellyError.AccessDenied)
        currentUser = landlord.copy(userId = "someone-else")
        assertTrue(listingService.getCompleteness("l-100").exceptionOrNull() is DwellyError.AccessDenied)
        currentUser = landlord
        // Everything but photos is filled: 5 of 6 items.
        assertEquals(83, listingService.getCompleteness("l-100").getOrThrow().score)
    }

    @Test
    fun `listing status transitions follow state machine`() = runBlocking {
        currentUser = landlord
        listingApi.createListing(sampleListing)
        val id = sampleListing.listingId!!

        // ACTIVE -> PAUSED
        val paused = listingService.changeStatus(id, ListingStatus.PAUSED).getOrThrow()
        assertEquals(ListingStatus.PAUSED, paused.status)

        // PAUSED -> ACTIVE
        val active = listingService.changeStatus(id, ListingStatus.ACTIVE).getOrThrow()
        assertEquals(ListingStatus.ACTIVE, active.status)

        // ACTIVE -> RENTED
        val rented = listingService.changeStatus(id, ListingStatus.RENTED).getOrThrow()
        assertEquals(ListingStatus.RENTED, rented.status)

        // RENTED -> ACTIVE is not allowed
        val invalidTransition = listingService.changeStatus(id, ListingStatus.ACTIVE)
        assertTrue(invalidTransition.isFailure)
        assertTrue(invalidTransition.exceptionOrNull() is DwellyError.RejectChange)

        // RENTED -> DELETED
        val deleted = listingService.changeStatus(id, ListingStatus.DELETED).getOrThrow()
        assertEquals(ListingStatus.DELETED, deleted.status)

        // DELETED -> any transition is rejected
        val resurrect = listingService.changeStatus(id, ListingStatus.ACTIVE)
        assertTrue(resurrect.isFailure)
        assertTrue(resurrect.exceptionOrNull() is DwellyError.RejectChange)
    }

    @Test
    fun `completeness score with all 6 items filled gives 100`() {
        val completeness = listingService.score(sampleListing, photoCount = 2)
        assertEquals(100, completeness.score)
        assertTrue(completeness.suggestions.isEmpty())
    }

    @Test
    fun `completeness score with bare minimum gives suggestions`() {
        val bareListing = Listing(
            landlordId = landlord.userId,
            title = "Bare flat",
            address = "Whitefield",
            lat = 12.9,
            lng = 77.7,
            rent = 15000.0,
            propertyType = PropertyType.APARTMENT,
        )
        val completeness = listingService.score(bareListing, photoCount = 0)
        assertEquals(0, completeness.score)
        assertEquals(6, completeness.suggestions.size)
    }

    @Test
    fun `getMyListings excludes deleted and includes completeness`() = runBlocking {
        currentUser = landlord
        val activeListing = sampleListing.copy(listingId = "l-1")
        val deletedListing = sampleListing.copy(listingId = "l-2", status = ListingStatus.DELETED)
        listingApi.listings["l-1"] = activeListing
        listingApi.listings["l-2"] = deletedListing

        val result = listingService.getMyListings()
        assertTrue(result.isSuccess)
        val overviews = result.getOrThrow()
        assertEquals(1, overviews.size)
        assertEquals("l-1", overviews[0].listing.listingId)
    }

    @Test
    fun `recordStat updates stats for view, save and enquiry`() = runBlocking {
        currentUser = tenant
        listingApi.createListing(sampleListing)
        val id = sampleListing.listingId!!

        listingService.recordStat(StatMetric.VIEW, id)
        listingService.recordStat(StatMetric.SAVE, id, saved = true)
        listingService.recordStat(StatMetric.ENQUIRY, id, message = "Available?")

        val stats = listingApi.getStats(id)
        assertEquals(1, stats?.views)
        assertEquals(1, stats?.saves)
        assertEquals(1, stats?.enquiries)
    }
}
