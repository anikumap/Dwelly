package com.example.dwelly.ui.landlord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.PropertyType
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.launch

class LandlordDashboardViewModel(private val dwelly: DwellyService) : ViewModel() {
    var listings by mutableStateOf<List<ListingOverview>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            dwelly.getMyListings()
                .onSuccess { listings = it; message = null }
                .onFailure { message = it.message }
        }
    }

    fun setStatus(listingId: String, status: ListingStatus) {
        viewModelScope.launch {
            dwelly.setListingStatus(listingId, status)
                .onSuccess { load() }
                .onFailure { message = it.message }
        }
    }
}

@Composable
fun LandlordDashboardScreen(dwelly: DwellyService, onLogout: () -> Unit) {
    val vm = viewModel { LandlordDashboardViewModel(dwelly) }
    var showCreateListing by rememberSaveable { mutableStateOf(false) }
    var agreementListingId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Landlord Dashboard", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onLogout) { Text("Log out") }
        }

        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(8.dp))
        Button(onClick = { showCreateListing = true }) { Text("Create Listing") }

        if (showCreateListing) {
            CreateListingDialog(
                dwelly = dwelly,
                onDismiss = {
                    showCreateListing = false
                    vm.load()
                },
            )
        }

        agreementListingId?.let { listingId ->
            LandlordAgreementDialog(
                dwelly = dwelly,
                listingId = listingId,
                onDismiss = {
                    agreementListingId = null
                    vm.load()
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.listings, key = { it.listing.listingId ?: it.listing.title }) { overview ->
                val listing = overview.listing
                val listingId = listing.listingId.orEmpty()

                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(listing.title, style = MaterialTheme.typography.titleMedium)
                        Text("Status: ${listing.status.name} | Rent: INR ${listing.rent}")
                        Text("Views: ${overview.stats.views} | Saves: ${overview.stats.saves} | Enquiries: ${overview.stats.enquiries}")
                        Text("Completeness: ${overview.completeness.score}%")
                        if (overview.completeness.suggestions.isNotEmpty()) {
                            Text("Suggestions: ${overview.completeness.suggestions.joinToString("; ")}")
                        }

                        Row(Modifier.padding(top = 8.dp)) {
                            if (listing.status == ListingStatus.ACTIVE) {
                                Button(onClick = { vm.setStatus(listingId, ListingStatus.PAUSED) }) { Text("Pause") }
                                Spacer(Modifier.padding(horizontal = 2.dp))
                                Button(onClick = { vm.setStatus(listingId, ListingStatus.RENTED) }) { Text("Mark Rented") }
                            } else if (listing.status == ListingStatus.PAUSED) {
                                Button(onClick = { vm.setStatus(listingId, ListingStatus.ACTIVE) }) { Text("Resume") }
                                Spacer(Modifier.padding(horizontal = 2.dp))
                                Button(onClick = { vm.setStatus(listingId, ListingStatus.RENTED) }) { Text("Mark Rented") }
                            }

                            if (listing.status == ListingStatus.RENTED) {
                                Button(onClick = { agreementListingId = listingId }) { Text("Create Agreement") }
                            }

                            Spacer(Modifier.padding(horizontal = 2.dp))
                            TextButton(onClick = { vm.setStatus(listingId, ListingStatus.DELETED) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateListingDialog(dwelly: DwellyService, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var rent by rememberSaveable { mutableStateOf("15000") }
    var deposit by rememberSaveable { mutableStateOf("50000") }
    var maintenance by rememberSaveable { mutableStateOf("2000") }
    var bedrooms by rememberSaveable { mutableStateOf("2") }
    var bathrooms by rememberSaveable { mutableStateOf("2") }
    var carpetArea by rememberSaveable { mutableStateOf("900") }
    var propertyType by rememberSaveable { mutableStateOf(PropertyType.APARTMENT) }
    var msg by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Create Listing", style = MaterialTheme.typography.titleMedium)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") })
            OutlinedTextField(value = rent, onValueChange = { rent = it }, label = { Text("Rent (INR)") })
            OutlinedTextField(value = deposit, onValueChange = { deposit = it }, label = { Text("Deposit (INR)") })
            OutlinedTextField(value = maintenance, onValueChange = { maintenance = it }, label = { Text("Maintenance (INR)") })

            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    val r = rent.toDoubleOrNull() ?: 0.0
                    val d = deposit.toDoubleOrNull()
                    val m = maintenance.toDoubleOrNull()
                    val b = bedrooms.toIntOrNull()
                    val bath = bathrooms.toIntOrNull()
                    val area = carpetArea.toDoubleOrNull()

                    val newListing = Listing(
                        landlordId = "",
                        title = title,
                        description = description,
                        address = address,
                        lat = 12.9716,
                        lng = 77.5946,
                        rent = r,
                        deposit = d,
                        maintenance = m,
                        propertyType = propertyType,
                        bedrooms = b,
                        bathrooms = bath,
                        carpetArea = area,
                    )

                    scope.launch {
                        dwelly.createListing(newListing, emptyList())
                            .onSuccess { onDismiss() }
                            .onFailure { msg = it.message }
                    }
                }) {
                    Text("Save Listing")
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun LandlordAgreementDialog(dwelly: DwellyService, listingId: String, onDismiss: () -> Unit) {
    var tenantEmail by rememberSaveable { mutableStateOf("") }
    var agreement by mutableStateOf<Agreement?>(null)
    var msg by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Rental Agreement for Listing", style = MaterialTheme.typography.titleMedium)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (agreement == null) {
                OutlinedTextField(
                    value = tenantEmail,
                    onValueChange = { tenantEmail = it },
                    label = { Text("Tenant Email") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            dwelly.getTenantIdByEmail(tenantEmail)
                                .onSuccess { tenantId ->
                                    dwelly.createAgreement(listingId, tenantId)
                                        .onSuccess { ag -> agreement = ag; msg = "Agreement drafted" }
                                        .onFailure { msg = it.message }
                                }
                                .onFailure { msg = it.message }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Draft Agreement")
                }
            } else {
                val ag = agreement!!
                Text("Status: ${ag.status.name}")
                Text("Terms:")
                ag.terms.forEach { (k, v) ->
                    Text("$k: $v")
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row {
                    if (ag.status == com.example.dwelly.model.AgreementStatus.DRAFT) {
                        Button(onClick = {
                            scope.launch {
                                dwelly.sendAgreement(ag.agreementId)
                                    .onSuccess { agreement = it; msg = "Agreement sent to tenant!" }
                                    .onFailure { msg = it.message }
                            }
                        }) {
                            Text("Send to Tenant")
                        }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
