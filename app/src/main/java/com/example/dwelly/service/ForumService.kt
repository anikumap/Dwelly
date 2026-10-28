package com.example.dwelly.service

import com.example.dwelly.data.ForumApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.ReportedPost
import com.example.dwelly.model.Role
import com.example.dwelly.model.User

/**
 * Threads, posts and moderation (LOGIC §4.7). Tenants read and write, the Admin
 * reads and removes, Landlords have no forum access. The same rules are
 * enforced again by the SQL functions.
 */
class ForumService(
    private val api: ForumApi,
    private val currentUser: () -> User?,
) {
    /** Called by ListingService when a listing is created (LOGIC §4.1 step 5). */
    suspend fun createThread(listingId: String): Result<String> =
        asRole(Role.LANDLORD) { api.createThread(listingId) }

    suspend fun getThreads(): Result<List<ForumThread>> =
        asRole(Role.TENANT, Role.ADMIN) { api.getThreads() }

    suspend fun getPosts(threadId: String): Result<List<ForumPost>> =
        asRole(Role.TENANT, Role.ADMIN) { api.getPosts(threadId) }

    suspend fun post(threadId: String, text: String, replyTo: String? = null): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Write something first"))
        return asRole(Role.TENANT) { api.post(threadId, text.trim(), replyTo) }
    }

    suspend fun react(postId: String, reaction: String): Result<Unit> =
        asRole(Role.TENANT) { api.react(postId, reaction) }

    suspend fun report(postId: String): Result<Unit> =
        asRole(Role.TENANT) { api.report(postId) }

    suspend fun remove(postId: String): Result<Unit> =
        asRole(Role.ADMIN) { api.removePost(postId) }

    suspend fun getReportedPosts(): Result<List<ReportedPost>> =
        asRole(Role.ADMIN) { api.getReportedPosts() }

    private suspend fun <T> asRole(vararg roles: Role, block: suspend () -> T): Result<T> {
        val role = currentUser()?.role
        if (role == null || role !in roles) return Result.failure(DwellyError.AccessDenied())
        return serverCall(block)
    }

    companion object {
        const val LIKE = "LIKE"

        /**
         * Orders posts for display: each post is followed by its replies
         * (oldest first), with the reply depth. A reply whose parent is not in
         * the list (removed) is shown at the top level.
         */
        fun arrangeReplies(posts: List<ForumPost>): List<Pair<ForumPost, Int>> {
            val ids = posts.mapTo(HashSet()) { it.postId }
            val children = posts.filter { it.parentPostId in ids }.groupBy { it.parentPostId }
            val result = ArrayList<Pair<ForumPost, Int>>(posts.size)
            fun visit(post: ForumPost, depth: Int) {
                result += post to depth
                children[post.postId]?.forEach { visit(it, depth + 1) }
            }
            posts.filter { it.parentPostId == null || it.parentPostId !in ids }.forEach { visit(it, 0) }
            return result
        }
    }
}
