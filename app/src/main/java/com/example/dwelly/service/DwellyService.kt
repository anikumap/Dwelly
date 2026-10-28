package com.example.dwelly.service

import com.example.dwelly.data.AuthApi
import com.example.dwelly.data.ForumApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.Completeness
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingDetail
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.Location
import com.example.dwelly.model.ReportedPost
import com.example.dwelly.model.Role
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.TenantHome
import com.example.dwelly.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point: every use case in ARCHITECTURE §4 goes through here.
 * Person A owns authentication, the signed-in user and the forum. The other
 * operations delegate to the services owned by Person B and Person C.
 */
class DwellyService(
    private val authApi: AuthApi,
    forumApi: ForumApi,
) {
    private val _currentUser = MutableStateFlow<User?>(null)

    /** The signed-in user; null when signed out. The UI routes on its role. */
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val forumService = ForumService(forumApi) { _currentUser.value }
    private val listingService = ListingService(forumService) { _currentUser.value }
    private val discoveryService = DiscoveryService(listingService) { _currentUser.value }
    private val agreementService = AgreementService { _currentUser.value }

    // Authentication (Person A) ----------------------------------------------

    /** Signs back in from a stored session, if there is one. */
    suspend fun restoreSession(): Result<User?> = serverCall {
        val userId = authApi.currentUserId()
        val user = userId?.let { authApi.fetchUser(it) }
        _currentUser.value = user
        user
    }

    /**
     * Creates an account as LANDLORD or TENANT. Returns the signed-in user, or
     * null when the account must be confirmed by email before logging in.
     */
    suspend fun register(name: String, email: String, phone: String?, password: String, role: Role): Result<User?> {
        if (role == Role.ADMIN) return Result.failure(DwellyError.AccessDenied("Admin accounts cannot be registered"))
        return serverCall {
            val signedIn = authApi.signUp(name.trim(), email.trim(), phone?.trim()?.ifEmpty { null }, password, role)
            if (signedIn) loadSignedInUser() else null
        }
    }

    suspend fun login(email: String, password: String): Result<User> = serverCall {
        authApi.signIn(email.trim(), password)
        loadSignedInUser()
    }

    /** Always signs out locally, even if the server call fails. */
    suspend fun logout(): Result<Unit> = serverCall {
        try {
            authApi.signOut()
        } finally {
            _currentUser.value = null
        }
    }

    /** Tenant only. Writes the location to D1, where Discovery reads it. */
    suspend fun setLocation(location: Location): Result<Unit> {
        val tenant = _currentUser.value as? Tenant ?: return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            authApi.updateLocation(tenant.userId, location)
            _currentUser.value = tenant.copy(location = location)
        }
    }

    private suspend fun loadSignedInUser(): User {
        val userId = authApi.currentUserId() ?: throw DwellyError.AccessDenied()
        return authApi.fetchUser(userId).also { _currentUser.value = it }
    }

    // Community forum (Person A) ---------------------------------------------

    suspend fun getThreads(): Result<List<ForumThread>> = forumService.getThreads()

    suspend fun getPosts(threadId: String): Result<List<ForumPost>> = forumService.getPosts(threadId)

    suspend fun postInThread(threadId: String, text: String, replyTo: String? = null): Result<String> =
        forumService.post(threadId, text, replyTo)

    suspend fun reactToPost(postId: String, reaction: String): Result<Unit> = forumService.react(postId, reaction)

    suspend fun reportPost(postId: String): Result<Unit> = forumService.report(postId)

    suspend fun removePost(postId: String): Result<Unit> = forumService.remove(postId)

    suspend fun getReportedPosts(): Result<List<ReportedPost>> = forumService.getReportedPosts()

    // Listings (Person B) ----------------------------------------------------

    suspend fun createListing(listing: Listing, photos: List<ByteArray>): Result<Listing> =
        listingService.create(listing, photos)

    suspend fun updateListing(listing: Listing): Result<Listing> = listingService.update(listing)

    suspend fun setListingStatus(listingId: String, status: ListingStatus): Result<Listing> =
        listingService.changeStatus(listingId, status)

    suspend fun deleteListing(listingId: String): Result<Listing> =
        listingService.changeStatus(listingId, ListingStatus.DELETED)

    suspend fun uploadPhoto(listingId: String, bytes: ByteArray, position: Int): Result<ListingPhoto> =
        listingService.uploadPhoto(listingId, bytes, position)

    suspend fun getMyListings(): Result<List<ListingOverview>> = listingService.getMyListings()

    suspend fun getCompleteness(listingId: String): Result<Completeness> = listingService.getCompleteness(listingId)

    // Discovery (Person C) ---------------------------------------------------

    suspend fun browseFeed(sort: SortOrder, filter: FeedFilter = FeedFilter()): Result<List<Listing>> =
        discoveryService.feed(sort, filter)

    suspend fun getListingDetail(listingId: String): Result<ListingDetail> = discoveryService.detail(listingId)

    suspend fun sendEnquiry(listingId: String, message: String): Result<Unit> =
        discoveryService.sendEnquiry(listingId, message)

    suspend fun saveListing(listingId: String, saved: Boolean): Result<Unit> =
        discoveryService.saveListing(listingId, saved)

    suspend fun getHome(): Result<TenantHome> = discoveryService.getHome()

    // Agreements (Person C) --------------------------------------------------

    suspend fun createAgreement(listingId: String, tenantId: String): Result<Agreement> =
        agreementService.draft(listingId, tenantId)

    suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Result<Agreement> =
        agreementService.edit(agreementId, terms)

    suspend fun sendAgreement(agreementId: String): Result<Agreement> = agreementService.send(agreementId)

    suspend fun signAgreement(agreementId: String): Result<Agreement> = agreementService.sign(agreementId)

    suspend fun getAgreement(listingId: String): Result<Agreement> = agreementService.get(listingId)
}
