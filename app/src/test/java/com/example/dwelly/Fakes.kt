package com.example.dwelly

import com.example.dwelly.data.AuthApi
import com.example.dwelly.data.ForumApi
import com.example.dwelly.model.Admin
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.Landlord
import com.example.dwelly.model.Location
import com.example.dwelly.model.ReportedPost
import com.example.dwelly.model.Role
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

val tenant = Tenant("t1", "Tina", "tenant@test.dev", null, null)
val landlord = Landlord("l1", "Lal", "landlord@test.dev", "9000000002")
val admin = Admin("a1", "Ada", "admin@test.dev", null)

fun post(id: String, parent: String? = null) =
    ForumPost(id, "general", "t1", "Tina", parent, "body $id", "t", emptyMap())
