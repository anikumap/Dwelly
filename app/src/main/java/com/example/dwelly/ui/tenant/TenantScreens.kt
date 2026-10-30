package com.example.dwelly.ui.tenant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.FeedFilter
import com.example.dwelly.model.Listing
import com.example.dwelly.model.ListingDetail
import com.example.dwelly.model.Location
import com.example.dwelly.model.PropertyType
import com.example.dwelly.model.SortOrder
import com.example.dwelly.model.Tenant
import com.example.dwelly.model.TenantHome
import com.example.dwelly.service.DwellyService
import com.example.dwelly.ui.location.CurrentLocationResult
import com.example.dwelly.ui.location.problem
import com.example.dwelly.ui.location.rememberCurrentLocationRequest
import kotlinx.coroutines.launch
import java.io.File

class TenantHomeViewModel(private val dwelly: DwellyService) : ViewModel() {
    var homeData by mutableStateOf<TenantHome?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            dwelly.getHome()
                .onSuccess { homeData = it; message = null }
                .onFailure { message = it.message }
        }
    }
}

@Composable
fun TenantHomeScreen(dwelly: DwellyService, onOpenListingDetail: (String) -> Unit) {
    val vm = viewModel { TenantHomeViewModel(dwelly) }
    val user by dwelly.currentUser.collectAsState()
    val tenant = user as? Tenant

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineSmall)
        tenant?.location?.let {
            Text("Location: ${it.label}")
        } ?: Text("No location set (Set in Discover tab)")

        vm.message?.let { Text(it) }

        Spacer(Modifier.height(8.dp))

        when (val home = vm.homeData) {
            is TenantHome.Resident -> {
                Text("Your Rented Property:", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(home.property.listing.title, style = MaterialTheme.typography.titleMedium)
                        Text(home.property.listing.address)
                        Text("Rent: INR ${home.property.listing.rent}/mo")
                    }
                }
            }
            is TenantHome.Seeker -> {
                Text("Recently Viewed", style = MaterialTheme.typography.titleSmall)
                if (home.recentlyViewed.isEmpty()) {
                    Text("No recently viewed listings.")
                } else {
                    LazyColumn(Modifier.height(150.dp)) {
                        items(home.recentlyViewed) { listing ->
                            TextButton(onClick = { listing.listingId?.let(onOpenListingDetail) }) {
                                Text("${listing.title} - INR ${listing.rent}")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Popular Listings", style = MaterialTheme.typography.titleSmall)
                if (home.popular.isEmpty()) {
                    Text("No popular listings available.")
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(home.popular) { listing ->
                            TextButton(onClick = { listing.listingId?.let(onOpenListingDetail) }) {
                                Text("${listing.title} (${listing.propertyType.name}) - INR ${listing.rent}")
                            }
                        }
                    }
                }
            }
            null -> Text("Loading home data...")
        }
    }
}

class TenantDiscoveryViewModel(private val dwelly: DwellyService) : ViewModel() {
    var feed by mutableStateOf<List<Listing>>(emptyList())
        private set
    var sortOrder by mutableStateOf(SortOrder.LATEST)
    var filter by mutableStateOf(FeedFilter())
    var selectedListingDetail by mutableStateOf<ListingDetail?>(null)
    var promptLocationNeeded by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
        private set

    init {
        loadFeed()
    }

    fun loadFeed() {
        viewModelScope.launch {
            dwelly.browseFeed(sortOrder, filter)
                .onSuccess {
                    feed = it
                    promptLocationNeeded = false
                    message = null
                }
                .onFailure {
                    message = it.message
                    if (it is com.example.dwelly.model.DwellyError.PromptLocation) {
                        promptLocationNeeded = true
                    }
                }
        }
    }

    fun loadDetail(listingId: String) {
        viewModelScope.launch {
            dwelly.getListingDetail(listingId)
                .onSuccess { selectedListingDetail = it; message = null }
                .onFailure { message = it.message }
        }
    }

    fun saveListing(listingId: String, saved: Boolean) {
        viewModelScope.launch {
            dwelly.saveListing(listingId, saved)
                .onSuccess { message = if (saved) "Listing saved" else "Listing unsaved" }
                .onFailure { message = it.message }
        }
    }

    fun sendEnquiry(listingId: String, msg: String) {
        viewModelScope.launch {
            dwelly.sendEnquiry(listingId, msg)
                .onSuccess { message = "Enquiry sent!" }
                .onFailure { message = it.message }
        }
    }

    /** Saves the device's location as the tenant's location; with [sortNearest], sorts nearest first. */
    fun useCurrentLocation(lat: Double, lng: Double, sortNearest: Boolean) {
        if (sortNearest) sortOrder = SortOrder.NEAREST
        setLocation(CURRENT_LOCATION_LABEL, lat, lng)
    }

    fun showMessage(text: String) {
        message = text
    }

    fun setLocation(label: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            dwelly.setLocation(Location(label, lat, lng))
                .onSuccess {
                    promptLocationNeeded = false
                    loadFeed()
                }
                .onFailure { message = it.message }
        }
    }
}

@Composable
fun TenantDiscoveryScreen(dwelly: DwellyService, selectedListingId: String? = null) {
    val vm = viewModel { TenantDiscoveryViewModel(dwelly) }
    val user by dwelly.currentUser.collectAsState()
    val tenant = user as? Tenant
    val location = tenant?.location

    // LOGIC §1: the location can be changed at any time, so the form also opens on request.
    var editingLocation by rememberSaveable { mutableStateOf(false) }
    var locationInput by rememberSaveable { mutableStateOf("") }
    var latInput by rememberSaveable { mutableStateOf("") }
    var lngInput by rememberSaveable { mutableStateOf("") }
    val lat = latInput.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lng = lngInput.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    var enquiryMessage by rememberSaveable { mutableStateOf("") }
    var showEnquiryDialog by rememberSaveable { mutableStateOf(false) }

    // Whether the pending device-location request should also switch the sort to NEAREST.
    var sortNearestAfterLocating by remember { mutableStateOf(false) }
    val requestCurrentLocation = rememberCurrentLocationRequest { result ->
        if (result is CurrentLocationResult.Found) {
            vm.useCurrentLocation(result.lat, result.lng, sortNearest = sortNearestAfterLocating)
            editingLocation = false
        } else {
            result.problem()?.let(vm::showMessage)
        }
    }

    LaunchedEffect(selectedListingId) {
        if (selectedListingId != null) {
            vm.loadDetail(selectedListingId)
        }
    }

    // One scrolling list, so a long listing detail never pushes the feed off screen.
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Discover", style = MaterialTheme.typography.headlineSmall)
            if (location != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Location: ${location.label}", Modifier.weight(1f))
                    if (!editingLocation) {
                        TextButton(onClick = {
                            locationInput = location.label
                            latInput = location.lat.toString()
                            lngInput = location.lng.toString()
                            editingLocation = true
                        }) { Text("Change location") }
                    }
                }
            }

            if (vm.promptLocationNeeded || location == null || editingLocation) {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (location == null) "Set your location to see the feed:" else "Change your location:")
                        OutlinedTextField(
                            value = locationInput,
                            onValueChange = { locationInput = it },
                            label = { Text("Location name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = latInput,
                                onValueChange = { latInput = it },
                                label = { Text("Latitude") },
                                isError = latInput.isNotEmpty() && lat == null,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            OutlinedTextField(
                                value = lngInput,
                                onValueChange = { lngInput = it },
                                label = { Text("Longitude") },
                                isError = lngInput.isNotEmpty() && lng == null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = {
                                    vm.setLocation(locationInput.trim(), requireNotNull(lat), requireNotNull(lng))
                                    editingLocation = false
                                },
                                enabled = locationInput.isNotBlank() && lat != null && lng != null,
                            ) {
                                Text("Save location")
                            }
                            if (editingLocation) {
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                TextButton(onClick = { editingLocation = false }) { Text("Cancel") }
                            }
                        }
                        TextButton(onClick = {
                            sortNearestAfterLocating = false
                            requestCurrentLocation()
                        }) { Text("Use my current location") }
                    }
                }
            }

            vm.message?.let { Text(it) }

            // One option per line: four on a row didn't fit a phone and hid NEAREST.
            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            SortOrder.entries.forEach { order ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = vm.sortOrder == order,
                        onClick = {
                            vm.sortOrder = order
                            vm.loadFeed()
                        },
                    )
                    Text(sortLabel(order))
                }
            }
            TextButton(onClick = {
                sortNearestAfterLocating = true
                requestCurrentLocation()
            }) { Text("Nearest to my current location") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        vm.selectedListingDetail?.let { detail ->
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        ListingDetailBody(detail)

                        Row(Modifier.padding(top = 8.dp)) {
                            Button(onClick = { detail.listing.listingId?.let { vm.saveListing(it, true) } }) {
                                Text("Save")
                            }
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            TextButton(onClick = { detail.listing.listingId?.let { vm.saveListing(it, false) } }) {
                                Text("Unsave")
                            }
                        }
                        Row {
                            Button(onClick = { showEnquiryDialog = true }) {
                                Text("Enquire")
                            }
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            TextButton(onClick = { vm.selectedListingDetail = null }) {
                                Text("Close")
                            }
                        }

                        if (showEnquiryDialog) {
                            OutlinedTextField(
                                value = enquiryMessage,
                                onValueChange = { enquiryMessage = it },
                                label = { Text("Enquiry message") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    detail.listing.listingId?.let { vm.sendEnquiry(it, enquiryMessage) }
                                    showEnquiryDialog = false
                                    enquiryMessage = ""
                                },
                                enabled = enquiryMessage.isNotBlank(),
                            ) {
                                Text("Send Enquiry")
                            }
                        }
                    }
                }
            }
        }

        items(vm.feed, key = { it.listingId ?: it.title }) { listing ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(listing.title, style = MaterialTheme.typography.titleMedium)
                    Text("${listing.propertyType.name} - INR ${listing.rent.toInt()}/mo")
                    Text(listing.address)
                    TextButton(onClick = { listing.listingId?.let { vm.loadDetail(it) } }) {
                        Text("View Detail")
                    }
                }
            }
        }
    }
}

private const val CURRENT_LOCATION_LABEL = "Current location"

private fun sortLabel(order: SortOrder) = when (order) {
    SortOrder.LATEST -> "Latest"
    SortOrder.PRICE_LOW_HIGH -> "Price: low to high"
    SortOrder.PRICE_HIGH_LOW -> "Price: high to low"
    SortOrder.NEAREST -> "Nearest"
}

/** LOGIC §4.3 step 18: everything the landlord provided. Fields they left empty are skipped. */
@Composable
private fun ListingDetailBody(detail: ListingDetail) {
    val l = detail.listing
    Text(l.title, style = MaterialTheme.typography.titleLarge)
    Text("INR ${l.rent.toInt()}/mo - ${l.propertyType.name}")
    Text("Address: ${l.address}")
    l.description?.let { Text("Description: $it") }
    l.deposit?.let { Text("Deposit: INR ${it.toInt()}") }
    l.maintenance?.let { Text("Maintenance: INR ${it.toInt()}/mo") }
    l.availableFrom?.let { Text("Available from: $it") }
    l.leaseMonths?.let { Text("Lease: $it months") }
    l.bedrooms?.let { Text("Bedrooms: $it") }
    l.bathrooms?.let { Text("Bathrooms: $it") }
    l.balconies?.let { Text("Balconies: $it") }
    l.carpetArea?.let { Text("Carpet area: ${it.toInt()} sqft") }
    l.floorNo?.let { Text("Floor: $it") }
    l.furnishing?.let { Text("Furnishing: ${it.name}") }
    Text("Parking: ${l.parkingTwoWheeler} two-wheeler, ${l.parkingFourWheeler} four-wheeler")
    l.preferredTenant?.let { Text("Preferred tenant: ${it.name}") }
    if (l.amenities.isNotEmpty()) Text("Amenities: ${l.amenities.joinToString { it.name }}")
    if (l.rules.isNotEmpty()) Text("House rules: ${l.rules.joinToString { it.name }}")

    if (detail.photos.isNotEmpty()) {
        Text("Photos (${detail.photos.size}):")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            detail.photos.forEach { photo ->
                AsyncImage(
                    model = photo.url,
                    contentDescription = "Property photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
    }
}

class TenantAgreementViewModel(private val dwelly: DwellyService) : ViewModel() {
    var agreements by mutableStateOf<List<Agreement>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (dwelly.currentUser.value is Tenant) {
                dwelly.getMyAgreements()
                    .onSuccess {
                        agreements = it
                        message = null
                    }
                    .onFailure { message = it.message }
            }
        }
    }

    fun sign(agreementId: String) {
        viewModelScope.launch {
            dwelly.signAgreement(agreementId)
                .onSuccess { signed ->
                    agreements = agreements.map { if (it.agreementId == signed.agreementId) signed else it }
                    message = "Agreement signed!"
                }
                .onFailure { message = it.message }
        }
    }
}

@Composable
fun TenantAgreementScreen(dwelly: DwellyService) {
    val vm = viewModel { TenantAgreementViewModel(dwelly) }
    val context = LocalContext.current
    var pdfError by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Rental Agreements", style = MaterialTheme.typography.headlineSmall)
            vm.message?.let { Text(it) }
            pdfError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (vm.agreements.isEmpty()) {
                Text("No agreement yet. Your landlord's agreement appears here once they send it.")
            }
            Button(onClick = vm::load, modifier = Modifier.padding(top = 8.dp)) {
                Text("Refresh")
            }
        }

        // A tenant can be named on more than one; any still waiting for their signature come first.
        items(vm.agreements, key = { it.agreementId }) { ag ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    ag.terms["property"]?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text("Status: ${ag.status.name}", color = MaterialTheme.colorScheme.tertiary)
                    Text("Landlord Signed: ${ag.landlordSignedAt ?: "Pending"}")
                    Text("Tenant Signed: ${ag.tenantSignedAt ?: "Pending"}")

                    Spacer(Modifier.height(8.dp))
                    Text("Terms:")
                    ag.terms.toSortedMap().forEach { (key, value) ->
                        Text("$key: $value")
                    }

                    Spacer(Modifier.height(12.dp))
                    Row {
                        if (ag.status == com.example.dwelly.model.AgreementStatus.SENT && ag.tenantSignedAt == null) {
                            Button(onClick = { vm.sign(ag.agreementId) }) { Text("Sign Agreement") }
                            Spacer(Modifier.padding(horizontal = 4.dp))
                        }
                        Button(onClick = {
                            pdfError = if (downloadAgreementPdf(context, ag)) null else "No app on this device can open PDFs."
                        }) {
                            Text("Download PDF")
                        }
                    }
                }
            }
        }
    }
}

/** Writes the agreement to a PDF in the cache and opens it. Returns false if no app can open PDFs. */
fun downloadAgreementPdf(context: Context, agreement: Agreement): Boolean {
    val lines = buildList {
        add("RENTAL AGREEMENT")
        add("")
        add("Status: ${agreement.status.name}")
        add("Agreement ID: ${agreement.agreementId}")
        add("Landlord signed: ${agreement.landlordSignedAt ?: "not signed"}")
        add("Tenant signed: ${agreement.tenantSignedAt ?: "not signed"}")
        add("")
        agreement.terms.toSortedMap().forEach { (clause, text) ->
            addAll(wrap("$clause: $text", maxChars = 90))
            add("")
        }
    }

    val pdf = PdfDocument()
    val paint = Paint().apply { textSize = 10f }
    val lineHeight = 16f
    val linesPerPage = ((842 - 100) / lineHeight).toInt()
    lines.chunked(linesPerPage).forEachIndexed { index, pageLines ->
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
        pageLines.forEachIndexed { i, line -> page.canvas.drawText(line, 50f, 50f + i * lineHeight, paint) }
        pdf.finishPage(page)
    }

    val file = File(context.cacheDir, "agreement_${agreement.agreementId}.pdf")
    file.outputStream().use { pdf.writeTo(it) }
    pdf.close()

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "Open Agreement PDF"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun wrap(text: String, maxChars: Int): List<String> {
    val out = mutableListOf<String>()
    var line = StringBuilder()
    text.split(" ").forEach { word ->
        if (line.isNotEmpty() && line.length + 1 + word.length > maxChars) {
            out += line.toString()
            line = StringBuilder()
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
    }
    if (line.isNotEmpty()) out += line.toString()
    return out
}
