package com.example.dwelly.service

import com.example.dwelly.model.Agreement
import com.example.dwelly.model.User

/**
 * Person C. Process 4.0 Manage Rental Agreements: drafts the agreement from
 * the listing and tracks its status (LOGIC §3, §4.5).
 */
class AgreementService(
    private val currentUser: () -> User?,
) {
    suspend fun draft(listingId: String, tenantId: String): Result<Agreement> = TODO("Person C")

    suspend fun edit(agreementId: String, terms: Map<String, String>): Result<Agreement> = TODO("Person C")

    suspend fun send(agreementId: String): Result<Agreement> = TODO("Person C")

    suspend fun sign(agreementId: String): Result<Agreement> = TODO("Person C")

    suspend fun get(listingId: String): Result<Agreement> = TODO("Person C")
}
