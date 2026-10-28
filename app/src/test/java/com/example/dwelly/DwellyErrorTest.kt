package com.example.dwelly

import com.example.dwelly.model.DwellyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwellyErrorTest {
    @Test
    fun mapsEverySpecReply() {
        assertTrue(DwellyError.fromServerMessage("accessDenied") is DwellyError.AccessDenied)
        assertTrue(DwellyError.fromServerMessage("promptLocation") is DwellyError.PromptLocation)
        assertTrue(DwellyError.fromServerMessage("listingUnavailable") is DwellyError.ListingUnavailable)
        assertTrue(DwellyError.fromServerMessage("rejectChange: x") is DwellyError.RejectChange)
        assertTrue(DwellyError.fromServerMessage("rejectAgreement: y") is DwellyError.RejectAgreement)
    }

    @Test
    fun keepsTheReason() {
        val change = DwellyError.fromServerMessage("rejectChange: listing is RENTED") as DwellyError.RejectChange
        assertEquals("listing is RENTED", change.reason)
        val denied = DwellyError.fromServerMessage("accessDenied: role must be LANDLORD or TENANT") as DwellyError.AccessDenied
        assertEquals("role must be LANDLORD or TENANT", denied.detail)
    }

    @Test
    fun otherMessagesAreNotSpecReplies() {
        assertNull(DwellyError.fromServerMessage("post not found"))
        assertNull(DwellyError.fromServerMessage(null))
    }
}
