package com.example.dwelly

import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.User
import com.example.dwelly.service.AgreementService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgreementServiceTest {
    private val agreementApi = FakeAgreementApi()
    private var currentUser: User? = landlord
    private val agreementService = AgreementService(agreementApi) { currentUser }

    @Test
    fun landlordDraftsAnAgreement() = runBlocking {
        val agreement = agreementService.draft("l1", tenant.userId).getOrThrow()
        assertEquals("l1", agreement.listingId)
        assertEquals(AgreementStatus.DRAFT, agreement.status)
    }

    @Test
    fun tenantCannotDraftEditOrSend() = runBlocking {
        val drafted = agreementService.draft("l1", tenant.userId).getOrThrow()
        currentUser = tenant
        assertTrue(agreementService.draft("l1", tenant.userId).exceptionOrNull() is DwellyError.AccessDenied)
        assertTrue(agreementService.edit(drafted.agreementId, emptyMap()).exceptionOrNull() is DwellyError.AccessDenied)
        assertTrue(agreementService.send(drafted.agreementId).exceptionOrNull() is DwellyError.AccessDenied)
    }

    @Test
    fun landlordSendsThenTenantSigns() = runBlocking {
        val drafted = agreementService.draft("l1", tenant.userId).getOrThrow()
        val sent = agreementService.send(drafted.agreementId).getOrThrow()
        assertEquals(AgreementStatus.SENT, sent.status)

        currentUser = tenant
        val signed = agreementService.sign(sent.agreementId).getOrThrow()
        assertEquals(AgreementStatus.SIGNED, signed.status)
        assertNotNull(signed.landlordSignedAt)
        assertNotNull(signed.tenantSignedAt)
    }

    @Test
    fun landlordFindsTenantByEmail() = runBlocking {
        assertEquals("t1", agreementService.getTenantIdByEmail("tenant@test.dev").getOrThrow())
        assertTrue(agreementService.getTenantIdByEmail("nobody@test.dev").exceptionOrNull() is DwellyError.RejectAgreement)
    }

    @Test
    fun onlyLandlordsLookUpTenantsByEmail() = runBlocking {
        currentUser = tenant
        assertTrue(agreementService.getTenantIdByEmail("tenant@test.dev").exceptionOrNull() is DwellyError.AccessDenied)
    }

    @Test
    fun tenantSeesEveryAgreementWithTheOneToSignFirst() = runBlocking {
        val old = agreementService.draft("old", tenant.userId).getOrThrow()
        agreementService.send(old.agreementId)
        currentUser = tenant
        agreementService.sign(old.agreementId)
        currentUser = landlord
        val new = agreementService.draft("new", tenant.userId).getOrThrow()
        agreementService.send(new.agreementId)

        currentUser = tenant
        val mine = agreementService.getMyAgreements().getOrThrow()
        assertEquals(listOf("new", "old"), mine.map { it.listingId })
    }

    @Test
    fun signedOutCallerIsDenied() = runBlocking {
        currentUser = null
        assertTrue(agreementService.sign("ag-l1").exceptionOrNull() is DwellyError.AccessDenied)
        assertTrue(agreementService.getMyAgreements().exceptionOrNull() is DwellyError.AccessDenied)
    }
}
