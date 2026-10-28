package com.example.dwelly

import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.User
import com.example.dwelly.service.ForumService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumServiceTest {
    private val api = FakeForumApi()
    private var user: User? = tenant
    private val forum = ForumService(api) { user }

    private fun Result<*>.isAccessDenied() = exceptionOrNull() is DwellyError.AccessDenied

    @Test
    fun tenantPostsRepliesReactsAndReports() = runBlocking {
        assertTrue(forum.post("general", "  Hello  ").isSuccess)
        assertTrue(forum.post("general", "Re", replyTo = "p1").isSuccess)
        assertTrue(forum.react("p1", ForumService.LIKE).isSuccess)
        assertTrue(forum.report("p1").isSuccess)
        assertEquals(listOf("post:Hello:null", "post:Re:p1", "react:LIKE", "report"), api.calls)
    }

    @Test
    fun tenantCannotModerate() = runBlocking {
        assertTrue(forum.remove("p1").isAccessDenied())
        assertTrue(forum.getReportedPosts().isAccessDenied())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun landlordHasNoForumAccess() = runBlocking {
        user = landlord
        assertTrue(forum.getThreads().isAccessDenied())
        assertTrue(forum.getPosts("general").isAccessDenied())
        assertTrue(forum.post("general", "Hi").isAccessDenied())
        assertTrue(forum.react("p1", ForumService.LIKE).isAccessDenied())
        assertTrue(forum.report("p1").isAccessDenied())
        assertTrue(api.calls.isEmpty())
        assertTrue(forum.createThread("listing-1").isSuccess)
    }

    @Test
    fun adminReadsAndRemovesButDoesNotPost() = runBlocking {
        user = admin
        assertTrue(forum.getThreads().isSuccess)
        assertTrue(forum.getReportedPosts().isSuccess)
        assertTrue(forum.remove("p1").isSuccess)
        assertTrue(forum.post("general", "Hi").isAccessDenied())
    }

    @Test
    fun signedOutUserIsDenied() = runBlocking {
        user = null
        assertTrue(forum.getThreads().isAccessDenied())
    }

    @Test
    fun blankPostIsRejectedLocally() = runBlocking {
        assertTrue(forum.post("general", "   ").isFailure)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun serverErrorsComeBackAsResults() = runBlocking {
        val failing = object : com.example.dwelly.data.ForumApi by api {
            override suspend fun post(threadId: String, body: String, parentPostId: String?): String =
                throw DwellyError.ListingUnavailable()
        }
        val result = ForumService(failing) { tenant }.post("closed", "Hi")
        assertTrue(result.exceptionOrNull() is DwellyError.ListingUnavailable)
    }

    @Test
    fun repliesFollowTheirParent() {
        val arranged = ForumService.arrangeReplies(
            listOf(post("a"), post("b"), post("a1", "a"), post("a1x", "a1"), post("b1", "b"), post("orphan", "gone")),
        )
        assertEquals(
            listOf("a" to 0, "a1" to 1, "a1x" to 2, "b" to 0, "b1" to 1, "orphan" to 0),
            arranged.map { (p, depth) -> p.postId to depth },
        )
    }
}
