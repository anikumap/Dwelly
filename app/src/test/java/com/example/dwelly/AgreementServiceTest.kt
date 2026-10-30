package com.example.dwelly

import com.example.dwelly.model.AgreementStatus
import com.example.dwelly.service.AgreementService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgreementServiceTest {
    private val agreementApi = FakeAgreementApi()
    private val agreementService = AgreementService(agreementApi) { tenant }

    @Test
    fun draftAgreementCreatesDraft() = runBlocking {
        val agreement = agreementService.draft("l1", tenant.userId).getOrThrow()
        assertEquals("l1", agreement.listingId)
        assertEquals(AgreementStatus.DRAFT, agreement.status)
    }

    @Test
    fun sendAndSignAgreementTransitionsStatus() = runBlocking {
        val drafted = agreementService.draft("l1", tenant.userId).getOrThrow()
        val sent = agreementService.send(drafted.agreementId).getOrThrow()
        assertEquals(AgreementStatus.SENT, sent.status)

        val signed = agreementService.sign(sent.agreementId).getOrThrow()
        assertEquals(AgreementStatus.SIGNED, signed.status)
        assertNotNull(signed.landlordSignedAt)
        assertNotNull(signed.tenantSignedAt)
    }

    @Test
    fun getTenantIdByEmailFindsRegisteredTenant() = runBlocking {
        val tenantId = agreementService.getTenantIdByEmail("tenant@test.dev").getOrThrow()
        assertEquals("t1", tenantId)
    }
}
