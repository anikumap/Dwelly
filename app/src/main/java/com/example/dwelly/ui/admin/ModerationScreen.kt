package com.example.dwelly.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    Column(Modifier.padding(16.dp)) {
        Text("Reported posts", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::load) { Text("Refresh") }
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            ) { Text("Log out") }
        }
        vm.message?.let { Text(it) }
        if (vm.reported.isEmpty() && vm.message == null) Text("No reported posts")
        LazyColumn {
            items(vm.reported, key = { it.postId }) { post ->
                Column {
                    Text("Thread: ${post.listingTitle ?: "General"}", style = MaterialTheme.typography.titleMedium)
                    Text("Author: ${post.authorName}", color = MaterialTheme.colorScheme.secondary)
                    Text(post.body)
                    Text("Reports: ${post.reportCount}", color = MaterialTheme.colorScheme.tertiary)
                    Button(
                        onClick = { vm.remove(post.postId) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
                    ) { Text("Remove") }
                    HorizontalDivider()
                }
            }
        }
    }
}
