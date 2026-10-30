package com.example.dwelly.service

import com.example.dwelly.data.AgreementApi
import com.example.dwelly.data.serverCall
import com.example.dwelly.model.Agreement
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.User

/**
 * Person C. Process 4.0 Manage Rental Agreements: drafts the agreement from
 * the listing and tracks its status (LOGIC §3, §4.5).
 */
class AgreementService(
    private val agreementApi: AgreementApi? = null,
    private val currentUser: () -> User?,
) {
    private fun getApi(): AgreementApi = agreementApi ?: throw IllegalStateException("AgreementApi not provided")

    suspend fun draft(listingId: String, tenantId: String): Result<Agreement> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall { getApi().createAgreement(listingId, tenantId) }
    }

    suspend fun edit(agreementId: String, terms: Map<String, String>): Result<Agreement> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall { getApi().editAgreement(agreementId, terms) }
    }

    suspend fun send(agreementId: String): Result<Agreement> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall { getApi().sendAgreement(agreementId) }
    }

    suspend fun sign(agreementId: String): Result<Agreement> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall { getApi().signAgreement(agreementId) }
    }

    suspend fun get(listingId: String): Result<Agreement> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            getApi().getAgreementByListing(listingId) ?: throw DwellyError.AccessDenied("No agreement found for listing")
        }
    }

    suspend fun getMyAgreement(): Result<Agreement?> {
        val user = currentUser() ?: return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            getApi().getTenantAgreement(user.userId)
        }
    }

    suspend fun getTenantIdByEmail(email: String): Result<String> {
        if (currentUser() == null) return Result.failure(DwellyError.AccessDenied())
        return serverCall {
            getApi().getTenantIdByEmail(email) ?: throw DwellyError.RejectAgreement("No tenant found with email $email")
        }
    }
}
