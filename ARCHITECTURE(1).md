# Dwelly — Architecture

Rental management platform with two separate dashboards: Landlord and Tenant. This file defines **what the system is made of**. Behaviour is in `LOGIC.md`. Names are fixed across all diagrams; do not rename them and do not add features.

## 1. Purpose
Landlords manage only their own property listings and the rental agreement for each rented property. Tenants discover listings near their location, enquire, join forum discussions, and see their own agreement. Each side sees only what is relevant to it. Landlords cannot see other landlords' data; tenants cannot create listings.

## 2. Actors
| Actor | Type | Can do |
|---|---|---|
| Landlord | primary | create / edit / pause / delete listing, upload photos, view own listings and stats, view completeness score and suggestions, create and send rental agreement, view own agreement |
| Tenant | primary | set location, browse and filter listings, view listing detail, enquire, view Home, read / post / reply / react / report in forum, view and download own agreement, sign agreement |
| Admin | primary | remove reported forum posts |

All roles log in first. A Tenant gets one of two dashboards (TenantMode), **derived** at request time, never stored:
- **RESIDENT**: tenant has a rented property (a rental agreement exists for them). Home = own property.
- **SEEKER**: tenant has none. Home = recently viewed + popular listings.

Discovery and Community forum are identical in both modes.

## 3. Layers
```
Client (web UI: Landlord dashboard, Tenant dashboard [Resident | Seeker])
   |
Access layer: authentication + role check
   |
Service layer: DwellyService -> ListingService, DiscoveryService, AgreementService, ForumService, Listing, Agreement, ActivityStats
   |
Relational DB + photo storage
```

## 4. Components (class names are fixed)
| Component | Stereotype | Responsibility | Operations |
|---|---|---|---|
| User (abstract) | entity | login identity | login(), logout() |
| Landlord, Tenant, Admin | entity | role specialisations of User | see class diagram |
| Listing | entity | one property with details, amenities, rules, status | isOwnedBy(landlordId), pause(), resume(), markRented(), isLive() |
| ListingPhoto | entity | one photo of a listing | none |
| ListingStats | entity | views, saves, enquiries per listing | increment(metric) |
| Enquiry | entity | tenant's question about a listing | none |
| Agreement | entity | rental agreement of one rented listing | isParty(userId), send(), sign(userId) |
| ForumThread | entity | one thread per listing, plus one general thread | none |
| ForumPost | entity | post or reply inside a thread | react(userId), report(userId), remove() |
| DwellyService | control | single entry point; orchestrates every use case | register, login, createListing, updateListing, setListingStatus, deleteListing, uploadPhoto, getMyListings, getCompleteness, setLocation, browseFeed, getListingDetail, sendEnquiry, saveListing, getHome, createAgreement, sendAgreement, signAgreement, getAgreement, getThreads, postInThread, reactToPost, reportPost, removePost |
| ListingService | control | listing rules, ownership, status changes, completeness score | create(), update(), changeStatus(), score() |
| DiscoveryService | control | tenant feed, filters, sorting, distance | feed(location, sort), detail(listingId) |
| AgreementService | control | drafts agreement from listing, tracks status | draft(listingId, tenantId), send(), sign() |
| ForumService | control | threads, posts, moderation | createThread(listingId), post(), report(), remove() |

Dependencies: User → DwellyService; DwellyService → ListingService, DiscoveryService, AgreementService, ForumService; ListingService manages Listing, ListingPhoto, ListingStats; AgreementService manages Agreement; ForumService manages ForumThread, ForumPost; ListingService → ForumService (creates thread when a listing first goes live).

## 5. Enumerations
- Role: LANDLORD, TENANT, ADMIN
- ListingStatus: ACTIVE, PAUSED, RENTED
- AgreementStatus: DRAFT, SENT, SIGNED
- TenantMode (derived): RESIDENT, SEEKER
- PropertyType: APARTMENT, INDEPENDENT_HOUSE, VILLA, STUDIO, PG_SHARED
- Furnishing: UNFURNISHED, SEMI_FURNISHED, FULLY_FURNISHED
- SortOrder: LATEST, PRICE_LOW_HIGH, PRICE_HIGH_LOW, NEAREST
- StatMetric: VIEW, SAVE, ENQUIRY
- Amenity (fixed list): WIFI, AC, GEYSER, WASHING_MACHINE, REFRIGERATOR, MODULAR_KITCHEN, LIFT, POWER_BACKUP, WATER_24X7, GATED_SOCIETY, SECURITY_CCTV, GYM, SWIMMING_POOL, CLUBHOUSE, PARK_PLAY_AREA
- HouseRule (fixed list): NO_PETS / PETS_ALLOWED, NO_SMOKING, NO_ALCOHOL, VEG_ONLY, FAMILY_ONLY / BACHELORS_ALLOWED / STUDENTS_ALLOWED, VISITORS_ALLOWED / VISITORS_RESTRICTED, QUIET_HOURS
- PreferredTenant: WORKING_PROFESSIONALS, FAMILIES, STUDENTS

## 6. Data model (13 tables)
| Table | Columns |
|---|---|
| users | user_id PK, name, email UNIQUE, phone, password_hash, role, location_label NULL, location_lat NULL, location_lng NULL |
| listings | listing_id PK, landlord_id FK→users, title, description, address, lat, lng, rent, deposit, maintenance, available_from, lease_months, property_type, bedrooms, bathrooms, balconies, carpet_area, floor_no, furnishing, parking_two_wheeler, parking_four_wheeler, amenities (JSON list), rules (JSON list), preferred_tenant, status, went_live_at NULL, created_at |
| listing_photos | photo_id PK, listing_id FK, url, position |
| listing_stats | listing_id PK/FK, views, saves, enquiries |
| listing_views | user_id FK, listing_id FK, last_viewed_at (composite PK) |
| listing_saves | user_id FK, listing_id FK (composite PK) |
| enquiries | enquiry_id PK, listing_id FK, tenant_id FK→users, message, created_at |
| agreements | agreement_id PK, listing_id FK UNIQUE, landlord_id FK, tenant_id FK, status, terms (JSON: lease, rent, deposit, maintenance, responsibilities, notice, inspection, inventory), landlord_signed_at NULL, tenant_signed_at NULL, created_at |
| forum_threads | thread_id PK, listing_id FK NULL (NULL = general thread), created_at |
| forum_posts | post_id PK, thread_id FK, author_id FK→users, parent_post_id FK NULL, body, removed (bool), created_at |
| post_reactions | post_id FK, user_id FK, reaction (composite PK) |
| post_reports | report_id PK, post_id FK, reporter_id FK→users, created_at |
| dummy seed | pre-loaded listings are ordinary rows in `listings` owned by a seeded Landlord user |

`ref` conventions: none; this system has no activity-log table.
DFD stores: D1 = users · D2 = listings + listing_photos + listing_stats + listing_views + listing_saves + enquiries · D3 = agreements · D4 = forum_threads + forum_posts + post_reactions + post_reports.

## 7. DFD processes (Level 1)
1.0 Authenticate & Authorize · 2.0 Manage Listings · 3.0 Discover Listings · 4.0 Manage Rental Agreements · 5.0 Run Community Forum.
Rule: a store is written only by its owning process (D1→1.0, D2→2.0, D3→4.0, D4→5.0). Other processes read it or send an update request to the owner. Process 3.0 reads D2 and sends view / save / enquiry update requests to 2.0.
Process 2.0 splits into 2.1 Validate Ownership, 2.2 Save Listing, 2.3 Store Photos, 2.4 Compute Completeness, 2.5 Update Status & Stats.

## 8. Security and integrity rules
1. Every Landlord query is filtered by `landlord_id` = caller. A Landlord can never read another Landlord's listings, stats or agreements.
2. A Tenant has no create / edit / delete path to listings.
3. Landlord contact details never appear in forum threads or posts.
4. Passwords stored as hashes. Every request is authenticated and role-checked.
5. An Agreement is readable only by its landlord and its tenant; both see the same document.
6. Only Admin may remove a forum post.

## 9. Out of scope (do not build)
Online payments, rent collection, e-signature provider integration, chat between users, notifications, reviews and ratings, tenant listing posts, maps beyond distance sorting, mobile app, multi-language.

## 10. Technology
Free choice. Needs only: a web UI, a REST-style service layer, a relational database with transactions, file or object storage for photos, and a browser geolocation prompt or manual location entry.
