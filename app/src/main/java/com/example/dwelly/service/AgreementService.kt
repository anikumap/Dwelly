package com.example.dwelly.service

import com.example.dwelly.data.AgreementApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Role
import com.example.dwelly.model.User

/**
 * Person C. Process 4.0 Agreements: one agreement per RENTED listing, drafted
 * by the landlord, sent, then signed by both parties (LOGIC §3, §4.5).
 * The SQL functions enforce every rule; these checks only fail fast.
 */
class AgreementService(
    private val agreementApi: AgreementApi,
    private val currentUser: () -> User?,
) {
    suspend fun draft(listingId: String, tenantId: String): Result<Agreement> = asLandlord {
        agreementApi.createAgreement(listingId, tenantId)
    }

    suspend fun edit(agreementId: String, terms: Map<String, String>): Result<Agreement> = asLandlord {
        agreementApi.editAgreement(agreementId, terms)
    }

    suspend fun send(agreementId: String): Result<Agreement> = asLandlord {
        agreementApi.sendAgreement(agreementId)
    }

    /** Landlord or the named tenant; the server checks which one the caller is. */
    suspend fun sign(agreementId: String): Result<Agreement> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD && user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())
        return serverCall { agreementApi.signAgreement(agreementId) }
    }

    /** The agreement of a listing, for either party. Null when none is visible yet. */
    suspend fun get(listingId: String): Result<Agreement?> {
        currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        return serverCall { agreementApi.getAgreementByListing(listingId) }
    }

    /** The signed-in tenant's SENT or SIGNED agreement, if any (LOGIC §4.5). */
    suspend fun getMyAgreement(): Result<Agreement?> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.TENANT) return Result.failure(DwellyError.AccessDenied())
        return serverCall { agreementApi.getTenantAgreement(user.userId) }
    }

    /** Lets a landlord name the tenant by email when drafting. */
    suspend fun getTenantIdByEmail(email: String): Result<String> = asLandlord {
        agreementApi.getTenantIdByEmail(email)
            ?: throw DwellyError.RejectAgreement("no tenant is registered with that email")
    }

    private suspend fun <T> asLandlord(block: suspend () -> T): Result<T> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        if (user.role != Role.LANDLORD) return Result.failure(DwellyError.AccessDenied())
        return serverCall { block() }
    }
}
