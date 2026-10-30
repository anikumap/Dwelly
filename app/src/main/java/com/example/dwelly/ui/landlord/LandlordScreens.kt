package com.example.dwelly.ui.landlord

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.dwelly.model.Amenity
import com.example.dwelly.model.Furnishing
import com.example.dwelly.model.HouseRule
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingOverview
import com.example.dwelly.model.ListingPhoto
import com.example.dwelly.model.ListingStatus
import com.example.dwelly.model.PreferredTenant
import com.example.dwelly.model.PropertyType
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.launch

class LandlordViewModel(private val dwelly: DwellyService) : ViewModel() {
    var overviews by mutableStateOf<List<ListingOverview>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() = runBusy { reload() }

    private suspend fun reload() {
        dwelly.getMyListings()
            .onSuccess { overviews = it }
            .onFailure { message = it.message }
    }

    /** One request at a time: [busy] is set before launching and always cleared afterwards. */
    private fun runBusy(block: suspend () -> Unit) {
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

    fun clearMessage() {
        message = null
    }

    fun pauseListing(listingId: String) = updateStatus(listingId, ListingStatus.PAUSED)

    fun resumeListing(listingId: String) = updateStatus(listingId, ListingStatus.ACTIVE)

    fun markRented(listingId: String) = updateStatus(listingId, ListingStatus.RENTED)

    fun deleteListing(listingId: String) = updateStatus(listingId, ListingStatus.DELETED)

    private fun updateStatus(listingId: String, status: ListingStatus) = runBusy {
        dwelly.setListingStatus(listingId, status)
            .onSuccess { reload() }
            .onFailure { message = it.message }
    }

    /**
     * Saves the listing first, then uploads the photos one by one. If an upload fails the
     * listing already exists, so [onPhotosFailed] reopens it for editing instead of leaving the
     * create form up, where a retry would create a second listing.
     */
    fun createListing(
        listing: Listing,
        photos: List<ByteArray>,
        onDone: () -> Unit,
        onPhotosFailed: (Listing) -> Unit,
    ) = runBusy {
        dwelly.createListing(listing)
            .onSuccess { created ->
                val error = uploadPhotos(requireNotNull(created.listingId), photos, firstPosition = 0)
                reload()
                if (error == null) {
                    onDone()
                } else {
                    message = error
                    onPhotosFailed(created)
                }
            }
            .onFailure { message = it.message ?: "Failed to create listing" }
    }

    fun updateListing(
        listing: Listing,
        newPhotos: List<ByteArray>,
        existingPhotos: List<ListingPhoto>,
        onDone: () -> Unit,
    ) = runBusy {
        dwelly.updateListing(listing)
            .onSuccess {
                val firstPosition = (existingPhotos.maxOfOrNull { it.position } ?: -1) + 1
                val error = uploadPhotos(requireNotNull(listing.listingId), newPhotos, firstPosition)
                reload()
                if (error == null) onDone() else message = error
            }
            .onFailure { message = it.message ?: "Failed to update listing" }
    }

    /** Returns null when every photo uploaded, otherwise the error. Stops at the first failure. */
    private suspend fun uploadPhotos(listingId: String, photos: List<ByteArray>, firstPosition: Int): String? {
        photos.forEachIndexed { index, bytes ->
            val failure = dwelly.uploadPhoto(listingId, bytes, firstPosition + index).exceptionOrNull()
            if (failure != null) {
                return "Listing saved, but photo ${index + 1} of ${photos.size} failed to upload " +
                    "(${failure.message}). The photos before it were saved; add the rest again."
            }
        }
        return null
    }
}

private sealed class LandlordViewMode {
    data object Dashboard : LandlordViewMode()
    data object Create : LandlordViewMode()
    data class Edit(val listing: Listing) : LandlordViewMode()
}

@Composable
fun LandlordScreen(
    dwelly: DwellyService,
    onCreateAgreement: (listingId: String) -> Unit,
    onLogout: () -> Unit,
) {
    val vm = viewModel { LandlordViewModel(dwelly) }
    var mode by remember { mutableStateOf<LandlordViewMode>(LandlordViewMode.Dashboard) }

    when (val currentMode = mode) {
        is LandlordViewMode.Dashboard -> {
            LandlordDashboardContent(
                vm = vm,
                onNewListing = {
                    vm.clearMessage()
                    mode = LandlordViewMode.Create
                },
                onEditListing = { listing ->
                    vm.clearMessage()
                    mode = LandlordViewMode.Edit(listing)
                },
                onCreateAgreement = onCreateAgreement,
                onLogout = onLogout,
            )
        }
        is LandlordViewMode.Create -> {
            ListingFormScreen(
                initialListing = null,
                existingPhotos = emptyList(),
                errorMessage = vm.message,
                busy = vm.busy,
                onSave = { listing, photos ->
                    vm.createListing(
                        listing = listing,
                        photos = photos,
                        onDone = { mode = LandlordViewMode.Dashboard },
                        onPhotosFailed = { created -> mode = LandlordViewMode.Edit(created) },
                    )
                },
                onCancel = {
                    vm.clearMessage()
                    mode = LandlordViewMode.Dashboard
                },
            )
        }
        is LandlordViewMode.Edit -> {
            val existingPhotos = vm.overviews
                .find { it.listing.listingId == currentMode.listing.listingId }?.photos
                ?: emptyList()
            ListingFormScreen(
                initialListing = currentMode.listing,
                existingPhotos = existingPhotos,
                errorMessage = vm.message,
                busy = vm.busy,
                onSave = { listing, photos ->
                    vm.updateListing(
                        listing = listing,
                        newPhotos = photos,
                        existingPhotos = existingPhotos,
                    ) { mode = LandlordViewMode.Dashboard }
                },
                onCancel = {
                    vm.clearMessage()
                    mode = LandlordViewMode.Dashboard
                },
            )
        }
    }
}

@Composable
private fun LandlordDashboardContent(
    vm: LandlordViewModel,
    onNewListing: () -> Unit,
    onEditListing: (Listing) -> Unit,
    onCreateAgreement: (listingId: String) -> Unit,
    onLogout: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Landlord Dashboard")
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onLogout) { Text("Log out") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onNewListing, enabled = !vm.busy) {
                Text("Add new listing")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = vm::load, enabled = !vm.busy) {
                Text("Refresh")
            }
        }

        vm.message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Error: $it")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (vm.overviews.isEmpty() && !vm.busy) {
            Text("No listings yet. Add a new listing to get started.")
        } else {
            LazyColumn {
                items(vm.overviews, key = { it.listing.listingId ?: it.listing.title }) { overview ->
                    ListingOverviewCard(
                        overview = overview,
                        onPause = { overview.listing.listingId?.let(vm::pauseListing) },
                        onResume = { overview.listing.listingId?.let(vm::resumeListing) },
                        onMarkRented = { overview.listing.listingId?.let(vm::markRented) },
                        onDelete = { overview.listing.listingId?.let(vm::deleteListing) },
                        onEdit = { onEditListing(overview.listing) },
                        onCreateAgreement = { overview.listing.listingId?.let(onCreateAgreement) },
                        enabled = !vm.busy,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListingOverviewCard(
    overview: ListingOverview,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMarkRented: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCreateAgreement: () -> Unit,
    enabled: Boolean,
) {
    val listing = overview.listing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (overview.photos.isNotEmpty()) {
                PhotoRow(overview.photos.map { it.url }, size = 100)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text("${listing.title} — ${listing.status}")
            Text("${listing.address} • Rent: ₹${listing.rent.toInt()}/mo • Type: ${listing.propertyType}")

            Spacer(modifier = Modifier.height(4.dp))
            Text("Stats: ${overview.stats.views} views • ${overview.stats.saves} saves • ${overview.stats.enquiries} enquiries")

            Spacer(modifier = Modifier.height(4.dp))
            Text("Completeness score: ${overview.completeness.score}%")
            LinearProgressIndicator(
                progress = { overview.completeness.score / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            if (overview.completeness.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Suggestions:")
                overview.completeness.suggestions.forEach { suggestion ->
                    Text("• $suggestion")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Status actions on one row, Edit and Delete on the next, so four buttons never
            // have to share a phone-width row.
            Row(modifier = Modifier.fillMaxWidth()) {
                when (listing.status) {
                    ListingStatus.ACTIVE -> {
                        Button(onClick = onPause, enabled = enabled) { Text("Pause") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = onMarkRented, enabled = enabled) { Text("Mark rented") }
                    }
                    ListingStatus.PAUSED -> {
                        Button(onClick = onResume, enabled = enabled) { Text("Resume") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = onMarkRented, enabled = enabled) { Text("Mark rented") }
                    }
                    ListingStatus.RENTED -> {
                        Button(onClick = onCreateAgreement, enabled = enabled) { Text("Create agreement") }
                    }
                    ListingStatus.DELETED -> {
                        // Soft deleted: no actions
                    }
                }
            }

            if (listing.status != ListingStatus.DELETED) {
                var confirmDelete by remember { mutableStateOf(false) }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { confirmDelete = true }, enabled = enabled) { Text("Delete") }
                }

                // Delete can't be undone (DELETED has no way back in LOGIC §2), so ask first.
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete listing?") },
                        text = { Text("\"${listing.title}\" will be removed for good. This can't be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                onDelete()
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListingFormScreen(
    initialListing: Listing?,
    existingPhotos: List<ListingPhoto>,
    errorMessage: String?,
    busy: Boolean,
    onSave: (Listing, List<ByteArray>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val isEdit = initialListing != null

    var title by rememberSaveable { mutableStateOf(initialListing?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initialListing?.description.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(initialListing?.address.orEmpty()) }
    var latText by rememberSaveable { mutableStateOf(initialListing?.lat?.toString().orEmpty()) }
    var lngText by rememberSaveable { mutableStateOf(initialListing?.lng?.toString().orEmpty()) }
    var rentText by rememberSaveable { mutableStateOf(initialListing?.rent?.toInt()?.toString().orEmpty()) }
    var depositText by rememberSaveable { mutableStateOf(initialListing?.deposit?.toInt()?.toString().orEmpty()) }
    var maintenanceText by rememberSaveable { mutableStateOf(initialListing?.maintenance?.toInt()?.toString().orEmpty()) }
    var availableFrom by rememberSaveable { mutableStateOf(initialListing?.availableFrom.orEmpty()) }
    var leaseMonthsText by rememberSaveable { mutableStateOf(initialListing?.leaseMonths?.toString().orEmpty()) }
    var propertyType by rememberSaveable { mutableStateOf(initialListing?.propertyType ?: PropertyType.APARTMENT) }
    var bedroomsText by rememberSaveable { mutableStateOf(initialListing?.bedrooms?.toString().orEmpty()) }
    var bathroomsText by rememberSaveable { mutableStateOf(initialListing?.bathrooms?.toString().orEmpty()) }
    var balconiesText by rememberSaveable { mutableStateOf(initialListing?.balconies?.toString().orEmpty()) }
    var carpetAreaText by rememberSaveable { mutableStateOf(initialListing?.carpetArea?.toInt()?.toString().orEmpty()) }
    var floorNoText by rememberSaveable { mutableStateOf(initialListing?.floorNo?.toString().orEmpty()) }
    var furnishing by rememberSaveable { mutableStateOf(initialListing?.furnishing) }
    var parkingTwoWheelerText by rememberSaveable { mutableStateOf(initialListing?.parkingTwoWheeler?.toString() ?: "0") }
    var parkingFourWheelerText by rememberSaveable { mutableStateOf(initialListing?.parkingFourWheeler?.toString() ?: "0") }
    var preferredTenant by rememberSaveable { mutableStateOf(initialListing?.preferredTenant) }

    // Lists rather than sets so rememberSaveable can put them in the saved-state Bundle.
    var selectedAmenities by rememberSaveable { mutableStateOf(initialListing?.amenities.orEmpty()) }
    var selectedRules by rememberSaveable { mutableStateOf(initialListing?.rules.orEmpty()) }

    // Picked photos are kept as content URI strings so they survive rotation; the bytes are
    // read only when the landlord saves.
    var pickedPhotos by rememberSaveable { mutableStateOf(listOf<String>()) }
    var photoError by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        pickedPhotos = (pickedPhotos + uris.map { it.toString() }).distinct()
    }

    val lat = latText.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lng = lngText.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val rent = rentText.toDoubleOrNull()?.takeIf { it > 0 }

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item {
            Text(if (isEdit) "Edit Listing" else "Create Listing")
            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title *") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address *") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude *") },
                    isError = latText.isNotEmpty() && lat == null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    label = { Text("Longitude *") },
                    isError = lngText.isNotEmpty() && lng == null,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = rentText,
                    onValueChange = { rentText = it },
                    label = { Text("Rent (₹/mo) *") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = depositText,
                    onValueChange = { depositText = it },
                    label = { Text("Deposit (₹)") },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = maintenanceText,
                    onValueChange = { maintenanceText = it },
                    label = { Text("Maintenance (₹)") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = leaseMonthsText,
                    onValueChange = { leaseMonthsText = it },
                    label = { Text("Lease (months)") },
                    modifier = Modifier.weight(1f),
                )
            }

            TextField(
                value = availableFrom,
                onValueChange = { availableFrom = it },
                label = { Text("Available from (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Property Type *")
            PropertyType.entries.forEach { pt ->
                Row {
                    RadioButton(selected = propertyType == pt, onClick = { propertyType = pt })
                    Text(pt.name)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = bedroomsText,
                    onValueChange = { bedroomsText = it },
                    label = { Text("Bedrooms") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = bathroomsText,
                    onValueChange = { bathroomsText = it },
                    label = { Text("Bathrooms") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = balconiesText,
                    onValueChange = { balconiesText = it },
                    label = { Text("Balconies") },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = carpetAreaText,
                    onValueChange = { carpetAreaText = it },
                    label = { Text("Carpet Area (sqft)") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = floorNoText,
                    onValueChange = { floorNoText = it },
                    label = { Text("Floor No") },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Furnishing")
            Row {
                RadioButton(selected = furnishing == null, onClick = { furnishing = null })
                Text("Not specified")
            }
            Furnishing.entries.forEach { f ->
                Row {
                    RadioButton(selected = furnishing == f, onClick = { furnishing = f })
                    Text(f.name)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = parkingTwoWheelerText,
                    onValueChange = { parkingTwoWheelerText = it },
                    label = { Text("2-Wheeler Parking") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = parkingFourWheelerText,
                    onValueChange = { parkingFourWheelerText = it },
                    label = { Text("4-Wheeler Parking") },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Preferred Tenant")
            Row {
                RadioButton(selected = preferredTenant == null, onClick = { preferredTenant = null })
                Text("Any")
            }
            PreferredTenant.entries.forEach { pt ->
                Row {
                    RadioButton(selected = preferredTenant == pt, onClick = { preferredTenant = pt })
                    Text(pt.name)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Amenities")
            Amenity.entries.forEach { amenity ->
                Row {
                    Checkbox(
                        checked = amenity in selectedAmenities,
                        onCheckedChange = { checked ->
                            selectedAmenities = if (checked) selectedAmenities + amenity else selectedAmenities - amenity
                        },
                    )
                    Text(amenity.name)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("House Rules & Preferences")
            HouseRule.entries.forEach { rule ->
                Row {
                    Checkbox(
                        checked = rule in selectedRules,
                        onCheckedChange = { checked ->
                            selectedRules = if (checked) selectedRules + rule else selectedRules - rule
                        },
                    )
                    Text(rule.name)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Photos")
            if (existingPhotos.isNotEmpty()) {
                Text("Saved:")
                PhotoRow(existingPhotos.map { it.url }, size = 90)
            }
            if (pickedPhotos.isNotEmpty()) {
                Text("To upload:")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    pickedPhotos.forEach { uri ->
                        Column {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Picked photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(90.dp),
                            )
                            TextButton(onClick = { pickedPhotos = pickedPhotos - uri }, enabled = !busy) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
            Button(onClick = { photoPickerLauncher.launch("image/*") }, enabled = !busy) {
                Text("Pick photos")
            }

            if (busy) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Saving listing to server...")
            }

            (photoError ?: errorMessage)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error: $it")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        photoError = null
                        val photoBytes = pickedPhotos.map { uri ->
                            runCatching {
                                context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        val unreadable = photoBytes.indexOfFirst { it == null }
                        if (unreadable >= 0) {
                            photoError = "Photo ${unreadable + 1} could not be read. Remove it and pick it again."
                            return@Button
                        }
                        val listing = Listing(
                            listingId = initialListing?.listingId,
                            landlordId = initialListing?.landlordId.orEmpty(),
                            title = title.trim(),
                            description = description.trim().ifEmpty { null },
                            address = address.trim(),
                            lat = requireNotNull(lat),
                            lng = requireNotNull(lng),
                            rent = requireNotNull(rent),
                            deposit = depositText.toDoubleOrNull(),
                            maintenance = maintenanceText.toDoubleOrNull(),
                            availableFrom = availableFrom.trim().ifEmpty { null },
                            leaseMonths = leaseMonthsText.toIntOrNull(),
                            propertyType = propertyType,
                            bedrooms = bedroomsText.toIntOrNull(),
                            bathrooms = bathroomsText.toIntOrNull(),
                            balconies = balconiesText.toIntOrNull(),
                            carpetArea = carpetAreaText.toDoubleOrNull(),
                            floorNo = floorNoText.toIntOrNull(),
                            furnishing = furnishing,
                            parkingTwoWheeler = parkingTwoWheelerText.toIntOrNull() ?: 0,
                            parkingFourWheeler = parkingFourWheelerText.toIntOrNull() ?: 0,
                            amenities = selectedAmenities,
                            rules = selectedRules,
                            preferredTenant = preferredTenant,
                            status = initialListing?.status ?: ListingStatus.ACTIVE,
                        )
                        // Uploaded photos are cleared so a later save doesn't upload them twice.
                        pickedPhotos = emptyList()
                        onSave(listing, photoBytes.filterNotNull())
                    },
                    enabled = !busy && title.isNotBlank() && address.isNotBlank() &&
                        lat != null && lng != null && rent != null,
                ) {
                    Text(if (isEdit) "Update" else "Save Listing")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel, enabled = !busy) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun PhotoRow(urls: List<String>, size: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        urls.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = "Property photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp),
            )
        }
    }
}
