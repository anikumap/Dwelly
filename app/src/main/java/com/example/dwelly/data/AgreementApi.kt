package com.example.dwelly.data

import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

interface AgreementApi {
    suspend fun createAgreement(listingId: String, tenantId: String): Agreement
    suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Agreement
    suspend fun sendAgreement(agreementId: String): Agreement
    suspend fun signAgreement(agreementId: String): Agreement
    suspend fun getAgreementByListing(listingId: String): Agreement?
    suspend fun getTenantAgreement(tenantId: String): Agreement?
    suspend fun getTenantIdByEmail(email: String): String?
}

class SupabaseAgreementApi(
    private val client: () -> SupabaseClient = { SupabaseProvider.client },
) : AgreementApi {

    override suspend fun createAgreement(listingId: String, tenantId: String): Agreement {
        val json = buildJsonObject {
            put("p_listing_id", listingId)
            put("p_tenant_id", tenantId)
        }
        return client().postgrest.rpc("create_agreement", json).decodeAs()
    }

    override suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Agreement {
        val json = buildJsonObject {
            put("p_agreement_id", agreementId)
            put("p_terms", Json.encodeToJsonElement(terms))
        }
        return client().postgrest.rpc("edit_agreement", json).decodeAs()
    }

    override suspend fun sendAgreement(agreementId: String): Agreement {
        val json = buildJsonObject {
            put("p_agreement_id", agreementId)
        }
        return client().postgrest.rpc("send_agreement", json).decodeAs()
    }

    override suspend fun signAgreement(agreementId: String): Agreement {
        val json = buildJsonObject {
            put("p_agreement_id", agreementId)
        }
        return client().postgrest.rpc("sign_agreement", json).decodeAs()
    }

    override suspend fun getAgreementByListing(listingId: String): Agreement? =
        try {
            client().from("agreements").select {
                filter { eq("listing_id", listingId) }
            }.decodeList<Agreement>().firstOrNull()
        } catch (_: Exception) {
            null
        }

    override suspend fun getTenantAgreement(tenantId: String): Agreement? =
        try {
            client().from("agreements").select {
                filter { eq("tenant_id", tenantId) }
            }.decodeList<Agreement>().firstOrNull {
                it.status == AgreementStatus.SENT || it.status == AgreementStatus.SIGNED
            }
        } catch (_: Exception) {
            null
        }

    override suspend fun getTenantIdByEmail(email: String): String? =
        try {
            client().postgrest.rpc("get_tenant_id_by_email", buildJsonObject { put("p_email", email) })
                .decodeAsOrNull<String>()
        } catch (_: Exception) {
            null
        }
}
