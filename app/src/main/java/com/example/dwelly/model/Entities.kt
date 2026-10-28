package com.example.dwelly.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Entities from ARCHITECTURE §4. Table-backed ones are @Serializable with the
// column names from §6, so they decode straight from Supabase rows.
// Timestamps and dates are kept as the ISO strings Postgres returns.

data class Location(val label: String, val lat: Double, val lng: Double)

/** Login identity. The concrete type is the user's Role. */
sealed class User {
    abstract val userId: String
    abstract val name: String
    abstract val email: String
    abstract val phone: String?
    abstract val role: Role
}

data class Landlord(
    override val userId: String,
    override val name: String,
    override val email: String,
    override val phone: String?,
) : User() {
    override val role get() = Role.LANDLORD
}

data class Tenant(
    override val userId: String,
    override val name: String,
    override val email: String,
    override val phone: String?,
    val location: Location?,
) : User() {
    override val role get() = Role.TENANT
}

data class Admin(
    override val userId: String,
    override val name: String,
    override val email: String,
    override val phone: String?,
) : User() {
    override val role get() = Role.ADMIN
}

@Serializable
data class Listing(
    @SerialName("listing_id") val listingId: String? = null,
    @SerialName("landlord_id") val landlordId: String,
    val title: String,
    val description: String? = null,
    val address: String,
    val lat: Double,
    val lng: Double,
    val rent: Double,
    val deposit: Double? = null,
    val maintenance: Double? = null,
    @SerialName("available_from") val availableFrom: String? = null,
    @SerialName("lease_months") val leaseMonths: Int? = null,
    @SerialName("property_type") val propertyType: PropertyType,
    val bedrooms: Int? = null,
    val bathrooms: Int? = null,
    val balconies: Int? = null,
    @SerialName("carpet_area") val carpetArea: Double? = null,
    @SerialName("floor_no") val floorNo: Int? = null,
    val furnishing: Furnishing? = null,
    @SerialName("parking_two_wheeler") val parkingTwoWheeler: Int = 0,
    @SerialName("parking_four_wheeler") val parkingFourWheeler: Int = 0,
    val amenities: List<Amenity> = emptyList(),
    val rules: List<HouseRule> = emptyList(),
    @SerialName("preferred_tenant") val preferredTenant: PreferredTenant? = null,
    val status: ListingStatus = ListingStatus.ACTIVE,
    @SerialName("went_live_at") val wentLiveAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ListingPhoto(
    @SerialName("photo_id") val photoId: String,
    @SerialName("listing_id") val listingId: String,
    val url: String,
    val position: Int,
)

@Serializable
data class ListingStats(
    @SerialName("listing_id") val listingId: String,
    val views: Int = 0,
    val saves: Int = 0,
    val enquiries: Int = 0,
)

@Serializable
data class Enquiry(
    @SerialName("enquiry_id") val enquiryId: String,
    @SerialName("listing_id") val listingId: String,
    @SerialName("tenant_id") val tenantId: String,
    val message: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Agreement(
    @SerialName("agreement_id") val agreementId: String,
    @SerialName("listing_id") val listingId: String,
    @SerialName("landlord_id") val landlordId: String,
    @SerialName("tenant_id") val tenantId: String,
    val status: AgreementStatus,
    /** Clause name -> text. The key set is defined by Person C. */
    val terms: Map<String, String> = emptyMap(),
    @SerialName("landlord_signed_at") val landlordSignedAt: String? = null,
    @SerialName("tenant_signed_at") val tenantSignedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** listingId == null is the general thread. */
data class ForumThread(
    val threadId: String,
    val listingId: String?,
    val listingTitle: String?,
    val createdAt: String,
)

/** A visible post. Authors are shown by name only (ARCHITECTURE §8.3). */
data class ForumPost(
    val postId: String,
    val threadId: String,
    val authorId: String,
    val authorName: String,
    val parentPostId: String?,
    val body: String,
    val createdAt: String,
    /** Reaction -> count. */
    val reactions: Map<String, Int>,
)

/** A post shown in the Admin moderation queue. */
data class ReportedPost(
    val postId: String,
    val threadId: String,
    val listingTitle: String?,
    val authorName: String,
    val body: String,
    val createdAt: String,
    val reportCount: Int,
)

// Result types for the Person B / Person C operations of DwellyService.
// Owners may extend these.

data class Completeness(val score: Int, val suggestions: List<String>)

data class ListingOverview(val listing: Listing, val stats: ListingStats, val completeness: Completeness)

data class ListingDetail(val listing: Listing, val photos: List<ListingPhoto>)

data class FeedFilter(
    val minRent: Double? = null,
    val maxRent: Double? = null,
    val propertyType: PropertyType? = null,
    val furnishing: Furnishing? = null,
    val bedrooms: Int? = null,
)

sealed class TenantHome {
    data class Resident(val property: ListingDetail) : TenantHome()
    data class Seeker(val recentlyViewed: List<Listing>, val popular: List<Listing>) : TenantHome()
}
