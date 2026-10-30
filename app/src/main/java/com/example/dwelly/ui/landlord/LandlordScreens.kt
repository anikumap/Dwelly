package com.example.dwelly.ui.landlord

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    fun load() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            message = null
            try {
                dwelly.getMyListings()
                    .onSuccess { overviews = it }
                    .onFailure { message = it.message }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun reloadListingsInternal() {
        dwelly.getMyListings()
            .onSuccess { overviews = it }
            .onFailure { message = it.message }
    }

    fun clearMessage() {
        message = null
    }

    fun pauseListing(listingId: String) = updateStatus(listingId, ListingStatus.PAUSED)

    fun resumeListing(listingId: String) = updateStatus(listingId, ListingStatus.ACTIVE)

    fun markRented(listingId: String) = updateStatus(listingId, ListingStatus.RENTED)

    fun deleteListing(listingId: String) = updateStatus(listingId, ListingStatus.DELETED)

    private fun updateStatus(listingId: String, status: ListingStatus) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                dwelly.setListingStatus(listingId, status)
                    .onSuccess { reloadListingsInternal() }
                    .onFailure { message = it.message }
            } catch (e: Exception) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }

    fun createListing(
        listing: Listing,
        photos: List<ByteArray>,
        photoUrls: List<String>,
        onDone: () -> Unit,
    ) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                dwelly.createListing(listing, photos, photoUrls)
                    .onSuccess {
                        reloadListingsInternal()
                        onDone()
                    }
                    .onFailure {
                        message = it.message ?: "Failed to create listing"
                        android.util.Log.e("Dwelly", "createListing failed", it)
                    }
            } finally {
                busy = false
            }
        }
    }

    fun updateListing(
        listing: Listing,
        newPhotos: List<ByteArray> = emptyList(),
        newPhotoUrls: List<String> = emptyList(),
        existingPhotoCount: Int = 0,
        onDone: () -> Unit,
    ) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                dwelly.updateListing(listing)
                    .onSuccess {
                        val listingId = requireNotNull(listing.listingId)
                        var nextPos = existingPhotoCount
                        newPhotos.forEach { bytes ->
                            dwelly.uploadPhoto(listingId, bytes, nextPos++)
                        }
                        newPhotoUrls.forEach { url ->
                            dwelly.addPhotoUrl(listingId, url, nextPos++)
                        }
                        reloadListingsInternal()
                        onDone()
                    }
                    .onFailure {
                        message = it.message ?: "Failed to update listing"
                        android.util.Log.e("Dwelly", "updateListing failed", it)
                    }
            } finally {
                busy = false
            }
        }
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
                onSave = { listing, photos, urls ->
                    vm.createListing(listing, photos, urls) { mode = LandlordViewMode.Dashboard }
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
                onSave = { listing, photos, urls ->
                    vm.updateListing(
                        listing = listing,
                        newPhotos = photos,
                        newPhotoUrls = urls,
                        existingPhotoCount = existingPhotos.size,
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
            // Photo strip — plain Row + horizontalScroll avoids LazyRow-in-LazyColumn conflicts
            if (overview.photos.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    overview.photos.forEach { photo ->
                        AsyncImage(
                            model = photo.url,
                            contentDescription = "Property photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
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

                Spacer(modifier = Modifier.width(4.dp))
                if (listing.status != ListingStatus.DELETED) {
                    OutlinedButton(onClick = onEdit, enabled = enabled) { Text("Edit") }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ListingFormScreen(
    initialListing: Listing?,
    existingPhotos: List<com.example.dwelly.model.ListingPhoto>,
    errorMessage: String?,
    busy: Boolean,
    onSave: (Listing, List<ByteArray>, List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val isEdit = initialListing != null

    var title by rememberSaveable { mutableStateOf(initialListing?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initialListing?.description.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(initialListing?.address.orEmpty()) }
    var latText by rememberSaveable { mutableStateOf(initialListing?.lat?.toString() ?: "12.9716") }
    var lngText by rememberSaveable { mutableStateOf(initialListing?.lng?.toString() ?: "77.5946") }
    var rentText by rememberSaveable { mutableStateOf(initialListing?.rent?.toInt()?.toString() ?: "20000") }
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

    var selectedAmenities by remember { mutableStateOf(initialListing?.amenities?.toSet() ?: emptySet()) }
    var selectedRules by remember { mutableStateOf(initialListing?.rules?.toSet() ?: emptySet()) }

    // Real photos: selected byte arrays from device storage and pasted URLs
    var selectedDevicePhotos by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var photoUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var photoUrlInput by rememberSaveable { mutableStateOf("") }

    val MAX_PHOTOS = 10
    val totalPhotos = existingPhotos.size + selectedDevicePhotos.size + photoUrls.size
    val atLimit = totalPhotos >= MAX_PHOTOS

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val remaining = MAX_PHOTOS - totalPhotos
        val toAdd = uris.take(remaining.coerceAtLeast(0))
        val newBytes = toAdd.mapNotNull { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }
        if (newBytes.isNotEmpty()) {
            selectedDevicePhotos = selectedDevicePhotos + newBytes
        }
    }

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
                    label = { Text("Latitude") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    label = { Text("Longitude") },
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
            Text("Photos (${totalPhotos}/${MAX_PHOTOS})", style = MaterialTheme.typography.titleSmall)

            if (atLimit) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Maximum 10 photos reached. Remove one to add more.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- Existing photos (edit mode) ---
            if (existingPhotos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Saved photos:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                // Row + horizontalScroll avoids LazyRow-in-LazyColumn subcomposition conflicts
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    existingPhotos.forEach { photo ->
                        AsyncImage(
                            model = photo.url,
                            contentDescription = "Saved photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(90.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }

            // --- New device photos ---
            if (selectedDevicePhotos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("From device:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedDevicePhotos.size) { idx ->
                        val bytes = selectedDevicePhotos[idx]
                        val bitmap = remember(bytes) {
                            try {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        }
                        Box(modifier = Modifier.size(90.dp)) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Device photo ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Photo", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(
                                onClick = {
                                    selectedDevicePhotos = selectedDevicePhotos.toMutableList().also { it.removeAt(idx) }
                                },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) { Text("✕", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            // --- URL photos ---
            if (photoUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("From links:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photoUrls.size) { idx ->
                        Box(modifier = Modifier.size(90.dp)) {
                            SubcomposeAsyncImage(
                                model = photoUrls[idx],
                                contentDescription = "URL photo ${idx + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                },
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Link Error", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                            )
                            TextButton(
                                onClick = { photoUrls = photoUrls.toMutableList().also { it.removeAt(idx) } },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) { Text("✕", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    enabled = !busy && !atLimit,
                ) {
                    Text("Pick from storage")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = photoUrlInput,
                    onValueChange = { photoUrlInput = it },
                    label = { Text("Or paste image URL") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val trimmed = photoUrlInput.trim()
                        if (trimmed.isNotBlank() && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                            photoUrls = photoUrls + trimmed
                            photoUrlInput = ""
                        }
                    },
                    enabled = !busy && photoUrlInput.isNotBlank() && !atLimit,
                ) {
                    Text("Add URL")
                }
            }

            if (busy) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Saving listing to server...")
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val rent = rentText.toDoubleOrNull() ?: 0.0
                        val lat = latText.toDoubleOrNull() ?: 0.0
                        val lng = lngText.toDoubleOrNull() ?: 0.0
                        val listing = Listing(
                            listingId = initialListing?.listingId,
                            landlordId = initialListing?.landlordId.orEmpty(),
                            title = title.trim(),
                            description = description.trim().ifEmpty { null },
                            address = address.trim(),
                            lat = lat,
                            lng = lng,
                            rent = rent,
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
                            amenities = selectedAmenities.toList(),
                            rules = selectedRules.toList(),
                            preferredTenant = preferredTenant,
                            status = initialListing?.status ?: ListingStatus.ACTIVE,
                        )
                        onSave(listing, selectedDevicePhotos, photoUrls)
                    },
                    enabled = !busy && title.isNotBlank() && address.isNotBlank() && (rentText.toDoubleOrNull() ?: 0.0) > 0,
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
