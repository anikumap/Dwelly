package com.example.dwelly.data

import com.example.dwelly.model.Agreement
import com.example.dwelly.model.AgreementStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Process 4.0 Agreements. Backed by the SQL functions in 0005_agreements.sql. */
interface AgreementApi {
    suspend fun createAgreement(listingId: String, tenantId: String): Agreement
    suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Agreement
    suspend fun sendAgreement(agreementId: String): Agreement
    suspend fun signAgreement(agreementId: String): Agreement
    suspend fun getAgreementByListing(listingId: String): Agreement?
    /** The tenant's SENT and SIGNED agreements, any still waiting for their signature first. */
    suspend fun getTenantAgreements(tenantId: String): List<Agreement>
    suspend fun getTenantIdByEmail(email: String): String?
}

class SupabaseAgreementApi(
    private val client: () -> SupabaseClient = { SupabaseProvider.client },
) : AgreementApi {

    override suspend fun createAgreement(listingId: String, tenantId: String): Agreement =
        client().postgrest.rpc("create_agreement", buildJsonObject {
            put("p_listing_id", listingId)
            put("p_tenant_id", tenantId)
        }).decodeAs()

    override suspend fun editAgreement(agreementId: String, terms: Map<String, String>): Agreement =
        client().postgrest.rpc("edit_agreement", buildJsonObject {
            put("p_agreement_id", agreementId)
            put("p_terms", Json.encodeToJsonElement(terms))
        }).decodeAs()

    override suspend fun sendAgreement(agreementId: String): Agreement =
        client().postgrest.rpc("send_agreement", buildJsonObject {
            put("p_agreement_id", agreementId)
        }).decodeAs()

    override suspend fun signAgreement(agreementId: String): Agreement =
        client().postgrest.rpc("sign_agreement", buildJsonObject {
            put("p_agreement_id", agreementId)
        }).decodeAs()

    /** RLS shows a landlord their own agreements and a tenant theirs from SENT onward. */
    override suspend fun getAgreementByListing(listingId: String): Agreement? =
        client().from("agreements").select {
            filter { eq("listing_id", listingId) }
        }.decodeSingleOrNull()

    override suspend fun getTenantAgreements(tenantId: String): List<Agreement> =
        client().from("agreements").select {
            filter {
                eq("tenant_id", tenantId)
                isIn("status", listOf(AgreementStatus.SENT.name, AgreementStatus.SIGNED.name))
            }
            order("created_at", Order.DESCENDING)
        }.decodeList<Agreement>().sortedBy { it.tenantSignedAt != null }

    override suspend fun getTenantIdByEmail(email: String): String? =
        client().postgrest.rpc("get_tenant_id_by_email", buildJsonObject {
            put("p_email", email.trim())
        }).decodeAsOrNull()
}
