package com.example.dwelly.ui.tenant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
        Text("Home")
        tenant?.location?.let {
            Text("Location: ${it.label}")
        } ?: Text("No location set (Set in Discover tab)")

        vm.message?.let { Text(it) }

        Spacer(Modifier.height(8.dp))

        when (val home = vm.homeData) {
            is TenantHome.Resident -> {
                Text("Your Rented Property:")
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(home.property.listing.title)
                        Text(home.property.listing.address)
                        Text("Rent: INR ${home.property.listing.rent}/mo")
                    }
                }
            }
            is TenantHome.Seeker -> {
                Text("Recently Viewed")
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
                Text("Popular Listings")
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

    var locationInput by rememberSaveable { mutableStateOf("") }
    var latInput by rememberSaveable { mutableStateOf("") }
    var lngInput by rememberSaveable { mutableStateOf("") }
    val lat = latInput.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lng = lngInput.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    var enquiryMessage by rememberSaveable { mutableStateOf("") }
    var showEnquiryDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedListingId) {
        if (selectedListingId != null) {
            vm.loadDetail(selectedListingId)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Discover")
            tenant?.location?.let {
                Text("Loc: ${it.label}")
            }
        }

        if (vm.promptLocationNeeded || tenant?.location == null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Set Location to view feed:")
                    OutlinedTextField(
                        value = locationInput,
                        onValueChange = { locationInput = it },
                        label = { Text("Location Name") },
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
                    Button(
                        onClick = { vm.setLocation(locationInput.trim(), requireNotNull(lat), requireNotNull(lng)) },
                        enabled = locationInput.isNotBlank() && lat != null && lng != null,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Set Location")
                    }
                }
            }
        }

        vm.message?.let { Text(it) }

        // Sort choices
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            SortOrder.entries.forEach { order ->
                RadioButton(
                    selected = vm.sortOrder == order,
                    onClick = {
                        vm.sortOrder = order
                        vm.loadFeed()
                    },
                )
                Text(order.name, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Listing Detail View if selected
        val detail = vm.selectedListingDetail
        if (detail != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(detail.listing.title)
                    Text("INR ${detail.listing.rent}/mo - ${detail.listing.propertyType.name}")
                    Text("Address: ${detail.listing.address}")
                    detail.listing.description?.let { Text("Description: $it") }

                    if (detail.photos.isNotEmpty()) {
                        Text("Photos (${detail.photos.size}):")
                        LazyColumn(Modifier.height(100.dp)) {
                            items(detail.photos) { photo ->
                                AsyncImage(model = photo.url, contentDescription = "Photo")
                            }
                        }
                    }

                    Row {
                        Button(onClick = { detail.listing.listingId?.let { vm.saveListing(it, true) } }) {
                            Text("Save")
                        }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        TextButton(onClick = { detail.listing.listingId?.let { vm.saveListing(it, false) } }) {
                            Text("Unsave")
                        }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Button(onClick = { showEnquiryDialog = true }) {
                            Text("Enquire")
                        }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        TextButton(onClick = { vm.selectedListingDetail = null }) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        if (showEnquiryDialog && detail != null) {
            OutlinedTextField(
                value = enquiryMessage,
                onValueChange = { enquiryMessage = it },
                label = { Text("Enquiry Message") },
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

        // Feed list
        LazyColumn(Modifier.weight(1f)) {
            items(vm.feed, key = { it.listingId ?: it.title }) { listing ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(listing.title)
                        Text("${listing.propertyType.name} - INR ${listing.rent}")
                        Text(listing.address)
                        TextButton(onClick = { listing.listingId?.let { vm.loadDetail(it) } }) {
                            Text("View Detail")
                        }
                    }
                }
            }
        }
    }
}

class TenantAgreementViewModel(private val dwelly: DwellyService) : ViewModel() {
    var agreement by mutableStateOf<Agreement?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (dwelly.currentUser.value is Tenant) {
                dwelly.getMyAgreement()
                    .onSuccess {
                        agreement = it
                        message = if (it == null) {
                            "No active agreement found. If your landlord created an agreement, make sure they clicked 'Send to Tenant'."
                        } else null
                    }
                    .onFailure { message = it.message }
            }
        }
    }

    fun sign() {
        val ag = agreement ?: return
        viewModelScope.launch {
            dwelly.signAgreement(ag.agreementId)
                .onSuccess { agreement = it; message = "Agreement signed!" }
                .onFailure { message = it.message }
        }
    }
}

@Composable
fun TenantAgreementScreen(dwelly: DwellyService) {
    val vm = viewModel { TenantAgreementViewModel(dwelly) }
    val context = LocalContext.current
    var pdfError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rental Agreement")
        vm.message?.let { Text(it) }
        pdfError?.let { Text(it) }

        val ag = vm.agreement
        if (ag != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Status: ${ag.status.name}")
                    Text("Landlord Signed: ${ag.landlordSignedAt ?: "Pending"}")
                    Text("Tenant Signed: ${ag.tenantSignedAt ?: "Pending"}")

                    Spacer(Modifier.height(8.dp))
                    Text("Terms:")
                    ag.terms.forEach { (key, value) ->
                        Text("$key: $value")
                    }

                    Spacer(Modifier.height(12.dp))
                    Row {
                        if (ag.status == com.example.dwelly.model.AgreementStatus.SENT && ag.tenantSignedAt == null) {
                            Button(onClick = vm::sign) { Text("Sign Agreement") }
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
        } else {
            Text("No active agreement found.")
            Button(onClick = vm::load, modifier = Modifier.padding(top = 8.dp)) {
                Text("Refresh")
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
