package com.example.dwelly.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.dwelly.model.Role
import com.example.dwelly.service.DwellyService
import com.example.dwelly.ui.admin.ModerationScreen
import com.example.dwelly.ui.agreement.LandlordAgreementScreen
import com.example.dwelly.ui.auth.LoginScreen
import com.example.dwelly.ui.auth.RegisterScreen
import com.example.dwelly.ui.forum.ThreadListScreen
import com.example.dwelly.ui.forum.ThreadScreen
import com.example.dwelly.ui.landlord.LandlordScreen
import com.example.dwelly.ui.tenant.TenantAgreementScreen
import com.example.dwelly.ui.tenant.TenantDiscoveryScreen
import com.example.dwelly.ui.tenant.TenantHomeScreen
import kotlinx.coroutines.launch

private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val LANDLORD = "landlord"
    const val TENANT = "tenant"
    const val ADMIN = "admin"
    const val THREAD = "thread/{threadId}?title={title}"
    const val CREATE_AGREEMENT = "agreement/create/{listingId}"

    fun thread(threadId: String, title: String) = "thread/$threadId?title=${Uri.encode(title)}"
    fun createAgreement(listingId: String) = "agreement/create/$listingId"
}

/**
 * Role-based routing: the start destination follows the signed-in user's role,
 * and the whole graph is rebuilt when that changes (login or logout).
 */
@Composable
fun DwellyNavHost(dwelly: DwellyService, modifier: Modifier = Modifier) {
    var restored by remember { mutableStateOf(false) }
    var startupError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        dwelly.restoreSession().onFailure { startupError = it.message }
        restored = true
    }
    val user by dwelly.currentUser.collectAsState()
    val scope = rememberCoroutineScope()
    val logout: () -> Unit = { scope.launch { dwelly.logout() } }

    if (!restored) {
        Text("Loading", modifier)
        return
    }

    val start = when (user?.role) {
        null -> Routes.LOGIN
        Role.LANDLORD -> Routes.LANDLORD
        Role.TENANT -> Routes.TENANT
        Role.ADMIN -> Routes.ADMIN
    }
    key(start) {
        val nav = rememberNavController()
        NavHost(nav, startDestination = start, modifier = modifier) {
            composable(Routes.LOGIN) {
                LoginScreen(dwelly, startupError, onRegister = { nav.navigate(Routes.REGISTER) })
            }
            composable(Routes.REGISTER) {
                RegisterScreen(dwelly, onBack = { nav.popBackStack() })
            }
            composable(Routes.LANDLORD) {
                LandlordScreen(
                    dwelly = dwelly,
                    onCreateAgreement = { listingId -> nav.navigate(Routes.createAgreement(listingId)) },
                    onLogout = logout,
                )
            }
            composable(
                Routes.CREATE_AGREEMENT,
                arguments = listOf(navArgument("listingId") { type = NavType.StringType }),
            ) { entry ->
                LandlordAgreementScreen(
                    dwelly = dwelly,
                    listingId = entry.arguments?.getString("listingId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.TENANT) {
                TenantDashboard(
                    dwelly,
                    onOpenThread = { id, title -> nav.navigate(Routes.thread(id, title)) },
                    onLogout = logout,
                )
            }
            composable(Routes.ADMIN) {
                ModerationScreen(dwelly, onLogout = logout)
            }
            composable(
                Routes.THREAD,
                arguments = listOf(
                    navArgument("threadId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                ThreadScreen(
                    dwelly,
                    threadId = entry.arguments?.getString("threadId").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

private enum class TenantTab(val label: String) { HOME("Home"), DISCOVERY("Discover"), FORUM("Forum"), AGREEMENT("Agreement") }

/**
 * Tenant dashboard. Discovery and Forum are the same for RESIDENT and SEEKER;
 * Home and Agreement are built by Person C.
 */
@Composable
private fun TenantDashboard(
    dwelly: DwellyService,
    onOpenThread: (threadId: String, title: String) -> Unit,
    onLogout: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TenantTab.HOME) }
    // A listing opened from Home is shown on the Discover tab.
    var selectedListingId by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            TenantTab.entries.forEach {
                Tab(selected = tab == it, onClick = { tab = it }, text = { Text(it.label) })
            }
        }
        Button(onClick = onLogout) { Text("Log out") }
        when (tab) {
            TenantTab.HOME -> TenantHomeScreen(
                dwelly = dwelly,
                onOpenListingDetail = { listingId ->
                    selectedListingId = listingId
                    tab = TenantTab.DISCOVERY
                },
            )
            TenantTab.DISCOVERY -> TenantDiscoveryScreen(dwelly = dwelly, selectedListingId = selectedListingId)
            TenantTab.FORUM -> ThreadListScreen(dwelly, onOpenThread)
            TenantTab.AGREEMENT -> TenantAgreementScreen(dwelly = dwelly)
        }
    }
}
