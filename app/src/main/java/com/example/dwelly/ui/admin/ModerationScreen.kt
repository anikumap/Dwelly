package com.example.dwelly.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dwelly.model.ReportedPost
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.launch

class ModerationViewModel(private val dwelly: DwellyService) : ViewModel() {
    var reported by mutableStateOf<List<ReportedPost>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            dwelly.getReportedPosts()
                .onSuccess { reported = it; message = null }
                .onFailure { message = it.message }
        }
    }

    fun remove(postId: String) {
        viewModelScope.launch {
            dwelly.removePost(postId)
                .onSuccess { load() }
                .onFailure { message = it.message }
        }
    }
}

/** Admin: reported forum posts, most reported first, with Remove (ARCHITECTURE §8.6). */
@Composable
fun ModerationScreen(dwelly: DwellyService, onLogout: () -> Unit) {
    val vm = viewModel { ModerationViewModel(dwelly) }
    Column {
        Text("Reported posts")
        Row {
            Button(onClick = vm::load) { Text("Refresh") }
            Button(onClick = onLogout) { Text("Log out") }
        }
        vm.message?.let { Text(it) }
        if (vm.reported.isEmpty() && vm.message == null) Text("No reported posts")
        LazyColumn {
            items(vm.reported, key = { it.postId }) { post ->
                Column {
                    Text("Thread: ${post.listingTitle ?: "General"}")
                    Text("Author: ${post.authorName}")
                    Text(post.body)
                    Text("Reports: ${post.reportCount}")
                    Button(onClick = { vm.remove(post.postId) }) { Text("Remove") }
                    HorizontalDivider()
                }
            }
        }
    }
}
