package com.example.dwelly

import com.example.dwelly.data.AgreementApi
import com.example.dwelly.data.AuthApi
import com.example.dwelly.data.DiscoveryApi
import com.example.dwelly.data.ForumApi
import com.example.dwelly.data.ListingApi
import com.example.dwelly.model.Admin
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.model.Completeness
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.Landlord
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStats
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.Location
import com.example.dwelly.model.ReportedPost
import com.example.dwelly.model.Role
import com.example.dwelly.model.StatMetric
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.User

/** In-memory accounts keyed by email; password is checked on sign-in. */
class FakeAuthApi(var confirmEmail: Boolean = false) : AuthApi {
    private class Account(val user: User, val password: String)

    private val accounts = mutableMapOf<String, Account>()
    var signedInId: String? = null
    var failSignOut = false
    val locations = mutableMapOf<String, Location>()

    fun add(user: User, password: String) {
        accounts[user.email] = Account(user, password)
    }

    override suspend fun signUp(name: String, email: String, phone: String?, password: String, role: Role): Boolean {
        val id = "id-$email"
        val user = if (role == Role.LANDLORD) Landlord(id, name, email, phone) else Tenant(id, name, email, phone, null)
        add(user, password)
        if (!confirmEmail) signedInId = id
        return !confirmEmail
    }

    override suspend fun signIn(email: String, password: String) {
        val account = accounts[email]
        if (account == null || account.password != password) throw DwellyError.AccessDenied("Invalid login credentials")
        signedInId = account.user.userId
    }

    override suspend fun signOut() {
        if (failSignOut) throw IllegalStateException("offline")
        signedInId = null
    }

    override suspend fun currentUserId(): String? = signedInId

    override suspend fun fetchUser(userId: String): User = accounts.values.first { it.user.userId == userId }.user

    override suspend fun updateLocation(userId: String, location: Location) {
        locations[userId] = location
    }
}

/** Records calls so tests can check that a denied request never reaches the server. */
class FakeForumApi : ForumApi {
    val calls = mutableListOf<String>()
    var posts = listOf<ForumPost>()

    override suspend fun createThread(listingId: String): String = "thread-$listingId".also { calls += "createThread" }
    override suspend fun getThreads(): List<ForumThread> =
        listOf(ForumThread("general", null, null, "t0")).also { calls += "getThreads" }
    override suspend fun getPosts(threadId: String): List<ForumPost> = posts.also { calls += "getPosts" }
    override suspend fun post(threadId: String, body: String, parentPostId: String?): String =
        "post-1".also { calls += "post:$body:$parentPostId" }
    override suspend fun react(postId: String, reaction: String) { calls += "react:$reaction" }
    override suspend fun report(postId: String) { calls += "report" }
    override suspend fun removePost(postId: String) { calls += "remove" }
    override suspend fun getReportedPosts(): List<ReportedPost> = emptyList<ReportedPost>().also { calls += "reported" }
}

class FakeDiscoveryApi : DiscoveryApi {
    val activeListings = mutableListOf<Listing>()
    val photos = mutableMapOf<String, List<ListingPhoto>>()
    val popularListings = mutableListOf<Listing>()
    val recentlyViewed = mutableMapOf<String, List<Listing>>()
    val savedListings = mutableMapOf<String, MutableList<String>>()

    override suspend fun getActiveListings(): List<Listing> = activeListings
    override suspend fun getListing(listingId: String): Listing? = activeListings.firstOrNull { it.listingId == listingId }
    override suspend fun getPhotos(listingId: String): List<ListingPhoto> = photos[listingId] ?: emptyList()
    override suspend fun getPopularListings(): List<Listing> = popularListings
    override suspend fun getRecentlyViewedListings(tenantId: String): List<Listing> = recentlyViewed[tenantId] ?: emptyList()
    override suspend fun getSavedListingIds(tenantId: String): List<String> = savedListings[tenantId] ?: emptyList()
}

class FakeAgreementApi : AgreementApi {
    val agreements = mutableMapOf<String, Agreement>()

    override suspend fun createAgreement(listingId: String, tenantId: String): Agreement {
        val ag = Agreement(
            agreementId = "ag-$listingId",
            listingId = listingId,
            landlordId = "l1",
            tenantId = tenantId,
            status = AgreementStatus.DRAFT,
            terms = mapOf("parties" to "Lal & Tina", "property" to "Flat 101", "rent_terms" to "15000"),
            createdAt = "2025-01-01T00:00:00Z",
        )
        agreements[ag.agreementId] = ag
        return ag
    }

    override suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Agreement {
        val ag = agreements[agreementId] ?: throw DwellyError.AccessDenied()
        if (ag.status != AgreementStatus.DRAFT) throw DwellyError.RejectAgreement("Only DRAFT can be edited")
        val updated = ag.copy(terms = terms)
        agreements[agreementId] = updated
        return updated
    }

    override suspend fun sendAgreement(agreementId: String): Agreement {
        val ag = agreements[agreementId] ?: throw DwellyError.AccessDenied()
        val updated = ag.copy(status = AgreementStatus.SENT)
        agreements[agreementId] = updated
        return updated
    }

    override suspend fun signAgreement(agreementId: String): Agreement {
        val ag = agreements[agreementId] ?: throw DwellyError.AccessDenied()
        val lSigned = ag.landlordSignedAt ?: "2025-01-02T00:00:00Z"
        val tSigned = "2025-01-02T00:00:00Z"
        val newStatus = AgreementStatus.SIGNED
        val updated = ag.copy(landlordSignedAt = lSigned, tenantSignedAt = tSigned, status = newStatus)
        agreements[agreementId] = updated
        return updated
    }

    override suspend fun getAgreementByListing(listingId: String): Agreement? =
        agreements.values.firstOrNull { it.listingId == listingId }

    override suspend fun getTenantAgreement(tenantId: String): Agreement? =
        agreements.values.firstOrNull { it.tenantId == tenantId && (it.status == AgreementStatus.SENT || it.status == AgreementStatus.SIGNED) }

    override suspend fun getTenantIdByEmail(email: String): String? =
        if (email == "tenant@test.dev") "t1" else null
}

class FakeListingApi : ListingApi {
    val listings = mutableMapOf<String, Listing>()
    val stats = mutableMapOf<String, ListingStats>()

    override suspend fun createListing(listing: Listing): Listing {
        val id = listing.listingId ?: "listing-${listings.size + 1}"
        val created = listing.copy(listingId = id)
        listings[id] = created
        stats[id] = ListingStats(id)
        return created
    }

    override suspend fun updateListing(listing: Listing): Listing {
        val id = listing.listingId ?: throw IllegalArgumentException()
        listings[id] = listing
        return listing
    }

    override suspend fun changeStatus(listingId: String, status: ListingStatus): Listing {
        val l = listings[listingId] ?: throw DwellyError.AccessDenied()
        val updated = l.copy(status = status)
        listings[listingId] = updated
        return updated
    }

    override suspend fun uploadPhoto(landlordId: String, listingId: String, bytes: ByteArray, position: Int): ListingPhoto {
        return ListingPhoto("p-$position", listingId, "url-$position", position)
    }

    override suspend fun getMyListings(landlordId: String): List<ListingOverview> {
        return listings.values.filter { it.landlordId == landlordId && it.status != ListingStatus.DELETED }
            .map { ListingOverview(it, stats[it.listingId] ?: ListingStats(it.listingId.orEmpty()), Completeness(100, emptyList())) }
    }

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> = emptyList()

    override suspend fun getListing(listingId: String): Listing = listings[listingId] ?: throw DwellyError.ListingUnavailable()

    override suspend fun recordStat(metric: StatMetric, listingId: String, message: String?, saved: Boolean) {}
}

val tenant = Tenant("t1", "Tina", "tenant@test.dev", null, null)
val landlord = Landlord("l1", "Lal", "landlord@test.dev", "9000000002")
val admin = Admin("a1", "Ada", "admin@test.dev", null)

fun post(id: String, parent: String? = null) =
    ForumPost(id, "general", "t1", "Tina", parent, "body $id", "t", emptyMap())
