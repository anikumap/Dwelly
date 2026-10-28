package com.example.dwelly.ui.forum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dwelly.model.ForumPost
import com.example.dwelly.model.ForumThread
import com.example.dwelly.service.DwellyService
import com.example.dwelly.service.ForumService
import kotlinx.coroutines.launch

class ThreadListViewModel(private val dwelly: DwellyService) : ViewModel() {
    var threads by mutableStateOf<List<ForumThread>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            dwelly.getThreads()
                .onSuccess { threads = it; message = null }
                .onFailure { message = it.message }
        }
    }
}

/** General thread plus one thread per ACTIVE listing. */
@Composable
fun ThreadListScreen(dwelly: DwellyService, onOpenThread: (threadId: String, title: String) -> Unit) {
    val vm = viewModel { ThreadListViewModel(dwelly) }
    Column {
        Text("Community forum")
        Button(onClick = vm::load) { Text("Refresh") }
        vm.message?.let { Text(it) }
        LazyColumn {
            items(vm.threads, key = { it.threadId }) { thread ->
                val title = thread.listingTitle ?: "General"
                TextButton(onClick = { onOpenThread(thread.threadId, title) }) { Text(title) }
            }
        }
    }
}

class ThreadViewModel(private val dwelly: DwellyService, private val threadId: String) : ViewModel() {
    /** Posts in display order, each with its reply depth. */
    var posts by mutableStateOf<List<Pair<ForumPost, Int>>>(emptyList())
        private set
    var replyTo by mutableStateOf<ForumPost?>(null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            dwelly.getPosts(threadId)
                .onSuccess { posts = ForumService.arrangeReplies(it) }
                .onFailure { message = it.message }
        }
    }

    fun post(text: String, onPosted: () -> Unit) = act {
        dwelly.postInThread(threadId, text, replyTo?.postId)
            .onSuccess { replyTo = null; onPosted(); load() }
    }

    fun like(post: ForumPost) = act {
        dwelly.reactToPost(post.postId, ForumService.LIKE).onSuccess { load() }
    }

    fun report(post: ForumPost) = act {
        dwelly.reportPost(post.postId).onSuccess { message = "Post reported" }
    }

    private fun act(block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                block().onFailure { message = it.message }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun ThreadScreen(dwelly: DwellyService, threadId: String, title: String, onBack: () -> Unit) {
    val vm = viewModel(key = threadId) { ThreadViewModel(dwelly, threadId) }
    var text by rememberSaveable { mutableStateOf("") }
    val names = vm.posts.associate { (post, _) -> post.postId to post.authorName }

    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title)
        }
        vm.message?.let { Text(it) }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.posts, key = { it.first.postId }) { (post, _) ->
                Column {
                    post.parentPostId?.let { Text("Reply to ${names[it] ?: "a removed post"}") }
                    Text(post.authorName)
                    Text(post.body)
                    if (post.reactions.isNotEmpty()) {
                        Text(post.reactions.entries.joinToString { "${it.key} ${it.value}" })
                    }
                    Row {
                        TextButton(onClick = { vm.like(post) }) { Text("Like") }
                        TextButton(onClick = { vm.replyTo = post }) { Text("Reply") }
                        TextButton(onClick = { vm.report(post) }) { Text("Report") }
                    }
                    HorizontalDivider()
                }
            }
        }
        vm.replyTo?.let {
            Row {
                Text("Replying to ${it.authorName}")
                TextButton(onClick = { vm.replyTo = null }) { Text("Cancel") }
            }
        }
        TextField(value = text, onValueChange = { text = it }, label = { Text("Write a post") })
        Button(onClick = { vm.post(text) { text = "" } }, enabled = text.isNotBlank() && !vm.busy) { Text("Post") }
    }
}
