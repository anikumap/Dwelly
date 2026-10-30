package com.example.dwelly.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dwelly.model.Role
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.launch

class AuthViewModel(private val dwelly: DwellyService) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** On success the signed-in user changes and navigation switches to the role's dashboard. */
    fun login(email: String, password: String) = run {
        dwelly.login(email, password).onFailure { message = it.message }
    }

    fun register(name: String, email: String, phone: String, password: String, role: Role) = run {
        dwelly.register(name, email, phone, password, role)
            .onSuccess { user -> if (user == null) message = "Account created. Confirm your email, then log in." }
            .onFailure { message = it.message }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun LoginScreen(dwelly: DwellyService, startupError: String?, onRegister: () -> Unit) {
    val vm = viewModel { AuthViewModel(dwelly) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dwelly", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.tertiary)
        TextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.login(email, password) }, enabled = !vm.busy) { Text("Log in") }
        TextButton(onClick = onRegister) { Text("Register") }
        (vm.message ?: startupError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun RegisterScreen(dwelly: DwellyService, onBack: () -> Unit) {
    val vm = viewModel { AuthViewModel(dwelly) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.TENANT) }

    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Register", style = MaterialTheme.typography.headlineSmall)
        TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        TextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        TextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("I am a", style = MaterialTheme.typography.titleSmall)
        for (option in listOf(Role.TENANT, Role.LANDLORD)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = role == option, onClick = { role = option })
                Text(if (option == Role.TENANT) "Tenant" else "Landlord")
            }
        }
        Button(
            onClick = { vm.register(name, email, phone, password, role) },
            enabled = !vm.busy && name.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
        ) { Text("Register") }
        TextButton(onClick = onBack) { Text("Back to log in") }
        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
