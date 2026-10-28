package com.example.dwelly.model

// Enumerations from ARCHITECTURE §5. Constant names match the Postgres enum
// labels in supabase/migrations/0001_schema.sql, so they serialize as-is.

enum class Role { LANDLORD, TENANT, ADMIN }

/** DELETED is plan.md decision D3 (soft delete); it is not in the spec enum. */
enum class ListingStatus { ACTIVE, PAUSED, RENTED, DELETED }

enum class AgreementStatus { DRAFT, SENT, SIGNED }

/** Derived at request time, never stored (plan.md decision D2). */
enum class TenantMode { RESIDENT, SEEKER }

enum class PropertyType { APARTMENT, INDEPENDENT_HOUSE, VILLA, STUDIO, PG_SHARED }

enum class Furnishing { UNFURNISHED, SEMI_FURNISHED, FULLY_FURNISHED }

enum class SortOrder { LATEST, PRICE_LOW_HIGH, PRICE_HIGH_LOW, NEAREST }

enum class StatMetric { VIEW, SAVE, ENQUIRY }

enum class Amenity {
    WIFI, AC, GEYSER, WASHING_MACHINE, REFRIGERATOR, MODULAR_KITCHEN, LIFT, POWER_BACKUP,
    WATER_24X7, GATED_SOCIETY, SECURITY_CCTV, GYM, SWIMMING_POOL, CLUBHOUSE, PARK_PLAY_AREA,
}

enum class HouseRule {
    NO_PETS, PETS_ALLOWED, NO_SMOKING, NO_ALCOHOL, VEG_ONLY,
    FAMILY_ONLY, BACHELORS_ALLOWED, STUDENTS_ALLOWED,
    VISITORS_ALLOWED, VISITORS_RESTRICTED, QUIET_HOURS,
}

enum class PreferredTenant { WORKING_PROFESSIONALS, FAMILIES, STUDENTS }
