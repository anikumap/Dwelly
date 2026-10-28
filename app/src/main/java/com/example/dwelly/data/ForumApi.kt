package com.example.dwelly.data

import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.model.ReportedPost
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process 5.0 Run Community Forum. Backed by the SQL functions in 0003_forum.sql. */
interface ForumApi {
    suspend fun createThread(listingId: String): String
    suspend fun getThreads(): List<ForumThread>
    suspend fun getPosts(threadId: String): List<ForumPost>
    suspend fun post(threadId: String, body: String, parentPostId: String?): String
    suspend fun react(postId: String, reaction: String)
    suspend fun report(postId: String)
    suspend fun removePost(postId: String)
    suspend fun getReportedPosts(): List<ReportedPost>
}

class SupabaseForumApi(private val client: () -> SupabaseClient = { SupabaseProvider.client }) : ForumApi {

    override suspend fun createThread(listingId: String): String =
        client().postgrest.rpc("create_thread", buildJsonObject { put("p_listing_id", listingId) })
            .decodeAs<String>()

    override suspend fun getThreads(): List<ForumThread> =
        client().postgrest.rpc("get_threads").decodeList<ThreadRow>().map {
            ForumThread(it.threadId, it.listingId, it.listingTitle, it.createdAt)
        }

    override suspend fun getPosts(threadId: String): List<ForumPost> =
        client().postgrest.rpc("get_posts", buildJsonObject { put("p_thread_id", threadId) })
            .decodeList<PostRow>().map {
                ForumPost(
                    it.postId, it.threadId, it.authorId, it.authorName, it.parentPostId,
                    it.body, it.createdAt, it.reactions,
                )
            }

    override suspend fun post(threadId: String, body: String, parentPostId: String?): String =
        client().postgrest.rpc(
            "post_in_thread",
            buildJsonObject {
                put("p_thread_id", threadId)
                put("p_body", body)
                put("p_parent_post_id", parentPostId)
            },
        ).decodeAs<String>()

    override suspend fun react(postId: String, reaction: String) {
        client().postgrest.rpc("react_to_post", buildJsonObject {
            put("p_post_id", postId)
            put("p_reaction", reaction)
        })
    }

    override suspend fun report(postId: String) {
        client().postgrest.rpc("report_post", buildJsonObject { put("p_post_id", postId) })
    }

    override suspend fun removePost(postId: String) {
        client().postgrest.rpc("remove_post", buildJsonObject { put("p_post_id", postId) })
    }

    override suspend fun getReportedPosts(): List<ReportedPost> =
        client().postgrest.rpc("get_reported_posts").decodeList<ReportedPostRow>().map {
            ReportedPost(
                it.postId, it.threadId, it.listingTitle, it.authorName, it.body,
                it.createdAt, it.reportCount.toInt(),
            )
        }
}

@Serializable
private data class ThreadRow(
    @SerialName("thread_id") val threadId: String,
    @SerialName("listing_id") val listingId: String? = null,
    @SerialName("listing_title") val listingTitle: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class PostRow(
    @SerialName("post_id") val postId: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("parent_post_id") val parentPostId: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    val reactions: Map<String, Int> = emptyMap(),
)

@Serializable
private data class ReportedPostRow(
    @SerialName("post_id") val postId: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("listing_title") val listingTitle: String? = null,
    @SerialName("author_name") val authorName: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("report_count") val reportCount: Long,
)
