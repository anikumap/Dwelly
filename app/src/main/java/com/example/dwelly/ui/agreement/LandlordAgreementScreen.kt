package com.example.dwelly.ui.agreement

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.launch

/** Landlord side of LOGIC §4.5 for one RENTED listing: draft, edit (DRAFT only), send, sign. */
class LandlordAgreementViewModel(private val dwelly: DwellyService, private val listingId: String) : ViewModel() {
    var agreement by mutableStateOf<Agreement?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        request { dwelly.getAgreement(listingId).onSuccess { agreement = it } }
    }

    fun draft(tenantEmail: String) = request {
        dwelly.getTenantIdByEmail(tenantEmail)
            .mapCatching { tenantId -> dwelly.createAgreement(listingId, tenantId).getOrThrow() }
            .onSuccess { agreement = it; message = "Agreement drafted." }
    }

    fun saveTerms(terms: Map<String, String>) = request {
        val id = agreement?.agreementId ?: return@request Result.success(Unit)
        dwelly.editAgreement(id, terms).onSuccess { agreement = it; message = "Clauses saved." }
    }

    fun send() = request {
        val id = agreement?.agreementId ?: return@request Result.success(Unit)
        dwelly.sendAgreement(id).onSuccess { agreement = it; message = "Agreement sent to the tenant." }
    }

    fun sign() = request {
        val id = agreement?.agreementId ?: return@request Result.success(Unit)
        dwelly.signAgreement(id).onSuccess { agreement = it; message = "You signed the agreement." }
    }

    /** One request at a time; a failure shows its reason and changes nothing. */
    private fun request(block: suspend () -> Result<*>) {
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
fun LandlordAgreementScreen(dwelly: DwellyService, listingId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "agreement-$listingId") { LandlordAgreementViewModel(dwelly, listingId) }
    var tenantEmail by rememberSaveable { mutableStateOf("") }

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Rental agreement")
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            vm.message?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it)
            }
            Spacer(modifier = Modifier.height(8.dp))

            val ag = vm.agreement
            if (ag == null) {
                Text("No agreement yet. Enter the email the tenant registered with.")
                TextField(
                    value = tenantEmail,
                    onValueChange = { tenantEmail = it },
                    label = { Text("Tenant email") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.draft(tenantEmail) },
                    enabled = !vm.busy && tenantEmail.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Draft agreement") }
            } else {
                AgreementBody(ag, busy = vm.busy, onSave = vm::saveTerms, onSend = vm::send, onSign = vm::sign)
            }
        }
    }
}

@Composable
private fun AgreementBody(
    ag: Agreement,
    busy: Boolean,
    onSave: (Map<String, String>) -> Unit,
    onSend: () -> Unit,
    onSign: () -> Unit,
) {
    Text("Status: ${ag.status}")
    Text("Landlord signed: ${ag.landlordSignedAt ?: "not yet"}")
    Text("Tenant signed: ${ag.tenantSignedAt ?: "not yet"}")
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    if (ag.status == AgreementStatus.DRAFT) {
        // Clauses are editable only while DRAFT (LOGIC §4.5).
        var terms by rememberSaveable(ag.agreementId, ag.terms) { mutableStateOf(ag.terms) }
        terms.toSortedMap().forEach { (clause, text) ->
            TextField(
                value = text,
                onValueChange = { terms = terms + (clause to it) },
                label = { Text(clause) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { onSave(terms) }, enabled = !busy && terms != ag.terms) { Text("Save clauses") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSend, enabled = !busy && terms == ag.terms) { Text("Send to tenant") }
        }
    } else {
        ag.terms.toSortedMap().forEach { (clause, text) -> Text("$clause: $text") }
        if (ag.status == AgreementStatus.SENT && ag.landlordSignedAt == null) {
            Button(onClick = onSign, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Sign agreement") }
        }
    }
}
