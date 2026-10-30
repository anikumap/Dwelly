package com.example.dwelly

import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.PropertyType
import com.example.dwelly.service.DwellyService
import com.example.dwelly.ui.landlord.LandlordViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LandlordViewModelTest {
    private val listingApi = FakeListingApi()
    private val auth = FakeAuthApi().apply { add(landlord, "pw") }
    private val dwelly = DwellyService(auth, FakeForumApi(), listingApi, FakeDiscoveryApi(), FakeAgreementApi())

    private val listing = Listing(
        listingId = "l-1",
        landlordId = landlord.userId,
        title = "2BHK",
        address = "Indiranagar",
        lat = 12.97,
        lng = 77.64,
        rent = 30000.0,
        propertyType = PropertyType.APARTMENT,
    )

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dwelly.login(landlord.email, "pw").getOrThrow()
        Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun status(vm: LandlordViewModel) = vm.overviews.single().listing.status

    @Test
    fun `status changes clear busy and reload the list`() = runBlocking {
        listingApi.createListing(listing)
        val vm = LandlordViewModel(dwelly)
        assertEquals(ListingStatus.ACTIVE, status(vm))

        vm.pauseListing("l-1")
        assertFalse(vm.busy)
        assertEquals(ListingStatus.PAUSED, status(vm))

        // A second change goes through: the old code stayed busy and ignored it.
        vm.markRented("l-1")
        assertFalse(vm.busy)
        assertEquals(ListingStatus.RENTED, status(vm))
    }

    @Test
    fun `rejected status change shows the reason and changes nothing`() = runBlocking {
        listingApi.createListing(listing.copy(status = ListingStatus.ACTIVE))
        val vm = LandlordViewModel(dwelly)
        vm.markRented("l-1")

        vm.resumeListing("l-1")
        assertFalse(vm.busy)
        assertNotNull(vm.message)
        assertEquals(ListingStatus.RENTED, status(vm))
    }

    @Test
    fun `create refreshes the list`() = runBlocking {
        val vm = LandlordViewModel(dwelly)
        var done = false
        vm.createListing(listing, emptyList(), onDone = { done = true }, onPhotosFailed = {})
        assertTrue(done)
        assertEquals(1, vm.overviews.size)
    }

    @Test
    fun `failed photo upload reopens the saved listing instead of creating it twice`() = runBlocking {
        listingApi.failPhotoUpload = true
        val vm = LandlordViewModel(dwelly)
        var reopened: Listing? = null
        vm.createListing(listing, listOf(byteArrayOf(1)), onDone = {}, onPhotosFailed = { reopened = it })

        assertEquals("l-1", reopened?.listingId)
        assertNotNull(vm.message)
        assertEquals(1, listingApi.listings.size)
        assertFalse(vm.busy)
    }

    @Test
    fun `photos added on edit start after the highest saved position`() = runBlocking {
        listingApi.createListing(listing)
        val vm = LandlordViewModel(dwelly)
        vm.updateListing(listing, listOf(byteArrayOf(1)), existingPhotos = emptyList()) {}
        vm.updateListing(listing, listOf(byteArrayOf(2)), existingPhotos = vm.overviews.single().photos) {}

        assertEquals(listOf(0, 1), listingApi.photos.getValue("l-1").map { it.position })
    }
}
