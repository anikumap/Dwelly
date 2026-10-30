package com.example.dwelly

import com.example.dwelly.model.DwellyError
import com.example.dwelly.model.Landlord
import com.example.dwelly.model.Location
import com.example.dwelly.model.Role
import com.example.dwelly.model.Tenant
import com.example.dwelly.service.DwellyService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwellyServiceTest {
    private val auth = FakeAuthApi().apply {
        add(tenant, "pw")
        add(landlord, "pw")
        add(admin, "pw")
    }
    private val dwelly = DwellyService(auth, FakeForumApi(), FakeListingApi(), FakeDiscoveryApi(), FakeAgreementApi())

    @Test
    fun loginLoadsTheUserWithTheirRole() = runBlocking {
        assertEquals(Role.ADMIN, dwelly.login(" admin@test.dev ", "pw").getOrThrow().role)
        assertEquals(admin, dwelly.currentUser.value)
    }

    @Test
    fun wrongPasswordIsAccessDenied() = runBlocking {
        val result = dwelly.login("tenant@test.dev", "nope")
        assertTrue(result.exceptionOrNull() is DwellyError.AccessDenied)
        assertNull(dwelly.currentUser.value)
    }

    @Test
    fun registerSignsInWhenNoConfirmationIsNeeded() = runBlocking {
        val user = dwelly.register("New", "new@test.dev", "", "pw", Role.LANDLORD).getOrThrow()
        assertTrue(user is Landlord)
        assertEquals(user, dwelly.currentUser.value)
        assertNull((user as Landlord).phone)
    }

    @Test
    fun registerWaitsForEmailConfirmation() = runBlocking {
        auth.confirmEmail = true
        assertNull(dwelly.register("New", "new@test.dev", "", "pw", Role.TENANT).getOrThrow())
        assertNull(dwelly.currentUser.value)
    }

    @Test
    fun adminCannotBeRegistered() = runBlocking {
        val result = dwelly.register("X", "x@test.dev", "", "pw", Role.ADMIN)
        assertTrue(result.exceptionOrNull() is DwellyError.AccessDenied)
    }

    @Test
    fun restoreSessionUsesTheStoredSession() = runBlocking {
        auth.signedInId = landlord.userId
        assertEquals(landlord, dwelly.restoreSession().getOrThrow())
    }

    @Test
    fun logoutClearsTheUserEvenWhenOffline() = runBlocking {
        dwelly.login("tenant@test.dev", "pw")
        auth.failSignOut = true
        assertTrue(dwelly.logout().isFailure)
        assertNull(dwelly.currentUser.value)
    }

    @Test
    fun onlyTenantsSetALocation() = runBlocking {
        val here = Location("Indiranagar", 12.97, 77.64)
        dwelly.login("landlord@test.dev", "pw")
        assertTrue(dwelly.setLocation(here).exceptionOrNull() is DwellyError.AccessDenied)

        dwelly.login("tenant@test.dev", "pw")
        assertTrue(dwelly.setLocation(here).isSuccess)
        assertEquals(here, (dwelly.currentUser.value as Tenant).location)
        assertEquals(here, auth.locations[tenant.userId])
    }
}
