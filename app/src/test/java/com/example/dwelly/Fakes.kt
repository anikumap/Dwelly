package com.example.dwelly

import com.example.dwelly.data.AuthApi
import com.example.dwelly.data.ForumApi
import com.example.dwelly.data.ListingApi
import com.example.dwelly.model.Admin
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.Landlord
import com.example.dwelly.model.Listing
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

/** Fake listing API for unit-testing ListingService without remote server. */
class FakeListingApi : ListingApi {
    val listings = mutableMapOf<String, Listing>()
    val photos = mutableMapOf<String, MutableList<ListingPhoto>>()
    val stats = mutableMapOf<String, ListingStats>()
    val calls = mutableListOf<String>()
    var failPhotoUpload = false

    override suspend fun createListing(listing: Listing): Listing {
        calls += "createListing:${listing.title}"
        val id = listing.listingId ?: "listing-${listings.size + 1}"
        val created = listing.copy(listingId = id, status = ListingStatus.ACTIVE)
        listings[id] = created
        stats[id] = ListingStats(id, 0, 0, 0)
        return created
    }

    override suspend fun updateListing(listing: Listing): Listing {
        val id = requireNotNull(listing.listingId)
        calls += "updateListing:$id"
        val existing = listings[id] ?: throw DwellyError.AccessDenied()
        if (existing.status == ListingStatus.DELETED) throw DwellyError.RejectChange("listing is DELETED")
        listings[id] = listing
        return listing
    }

    override suspend fun changeListingStatus(listingId: String, status: ListingStatus): Listing {
        calls += "changeListingStatus:$listingId:$status"
        val current = listings[listingId] ?: throw DwellyError.AccessDenied()
        val currStatus = current.status
        if (currStatus == status) throw DwellyError.RejectChange("cannot transition from $currStatus to $status")

        val allowed = when (currStatus) {
            ListingStatus.ACTIVE -> status in listOf(ListingStatus.PAUSED, ListingStatus.RENTED, ListingStatus.DELETED)
            ListingStatus.PAUSED -> status in listOf(ListingStatus.ACTIVE, ListingStatus.RENTED, ListingStatus.DELETED)
            ListingStatus.RENTED -> status == ListingStatus.DELETED
            ListingStatus.DELETED -> false
        }
        if (!allowed) throw DwellyError.RejectChange("cannot transition from $currStatus to $status")

        val updated = current.copy(status = status)
        listings[listingId] = updated
        return updated
    }

    override suspend fun getListing(listingId: String): Listing? = listings[listingId]

    override suspend fun getMyListings(landlordId: String): List<Listing> =
        listings.values.filter { it.landlordId == landlordId && it.status != ListingStatus.DELETED }

    override suspend fun getPhotos(listingId: String): List<ListingPhoto> =
        photos[listingId]?.toList() ?: emptyList()

    override suspend fun uploadPhoto(
        listingId: String,
        landlordId: String,
        bytes: ByteArray,
        position: Int,
    ): ListingPhoto {
        calls += "uploadPhoto:$listingId:$position"
        if (failPhotoUpload) throw IllegalStateException("storage offline")
        val list = photos.getOrPut(listingId) { mutableListOf() }
        val photo = ListingPhoto("photo-${list.size + 1}", listingId, "https://example.com/photos/$position.jpg", position)
        list.add(photo)
        return photo
    }

    override suspend fun getStats(listingId: String): ListingStats? = stats[listingId]

    override suspend fun recordStat(
        metric: StatMetric,
        listingId: String,
        message: String?,
        saved: Boolean?,
    ) {
        calls += "recordStat:$metric:$listingId"
        val current = stats.getOrPut(listingId) { ListingStats(listingId) }
        stats[listingId] = when (metric) {
            StatMetric.VIEW -> current.copy(views = current.views + 1)
            StatMetric.SAVE -> if (saved != false) current.copy(saves = current.saves + 1) else current.copy(saves = maxOf(0, current.saves - 1))
            StatMetric.ENQUIRY -> current.copy(enquiries = current.enquiries + 1)
        }
    }
}

val tenant = Tenant("t1", "Tina", "tenant@test.dev", null, null)
val landlord = Landlord("l1", "Lal", "landlord@test.dev", "9000000002")
val admin = Admin("a1", "Ada", "admin@test.dev", null)

fun post(id: String, parent: String? = null) =
    ForumPost(id, "general", "t1", "Tina", parent, "body $id", "t", emptyMap())
