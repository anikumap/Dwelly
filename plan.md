# Dwelly — Implementation Plan

This plan builds the system described in `ARCHITECTURE(1).md` and `LOGIC.md` as this Android (Jetpack Compose) app, with **Supabase** as the remote server. Spec names (classes, operations, enums, tables) are used unchanged. Nothing outside the specs is built.

## 1. Decisions

### 1.1 Remote server: Supabase

The spec asks for a relational database with transactions, photo storage, hashed passwords, and server-enforced access rules. Supabase covers all four:

| Spec need | Supabase feature |
|---|---|
| Relational DB with transactions, 13 tables | Postgres. Multi-step writes run as SQL functions (`rpc`), each in one transaction |
| REST-style service layer | PostgREST API, called through `supabase-kt` |
| Authentication, hashed passwords | Supabase Auth (email + password, bcrypt in `auth.users`) |
| "Every request is authenticated and role-checked" (ARCH §8) | Row Level Security (RLS) policies on every table |
| Photo storage | Supabase Storage bucket `listing-photos` |

Firebase was rejected because Firestore isn't relational and has no cross-document joins. The spec's `landlord_id` filtering and agreement-party checks would be harder to express in Firestore security rules.

### 1.2 Resolving the spec contradictions

These are the working decisions. Confirm them with the project owner before Phase 1 ends.

| # | Conflict | Decision |
|---|---|---|
| D1 | ARCH says web UI and lists "mobile app" as out of scope | Build the Android app. Supabase's REST API is the service backend. The "web UI" requirement becomes the Compose UI |
| D2 | TenantMode: ARCH says derived, LOGIC §1 says chosen at onboarding | **Derived** at request time: RESIDENT if an Agreement names the tenant with status SENT or SIGNED (LOGIC §4.6), else SEEKER. Never stored |
| D3 | LOGIC has a DELETED state, `ListingStatus` has no DELETED | Add `DELETED` to `ListingStatus` as a soft delete. Rows are never hard-deleted, so agreements and forum threads keep their foreign keys |
| D4 | LOGIC §5 "inserts a log row", ARCH has no activity-log table | The per-metric tables are the log: `listing_views` (VIEW), `listing_saves` (SAVE), `enquiries` (ENQUIRY). No new table |
| D5 | Listing detail is on `DiscoveryService.detail()` (ARCH) and `ListingService.getListingDetail()` (LOGIC) | `DwellyService.getListingDetail` → `DiscoveryService.detail` reads the listing, then sends the VIEW update to `ListingService` (DFD rule: only 2.0 writes D2) |
| D6 | `users.password_hash` | Held by Supabase Auth in `auth.users`. `public.users.user_id` = `auth.users.id`, and `public.users` has no password column |

## 2. Target architecture

```
Compose UI (Landlord dashboard | Tenant dashboard [Resident | Seeker] | Admin)
   |  ViewModels
DwellyService                          <- single entry point (Kotlin)
   |-- ListingService  DiscoveryService  AgreementService  ForumService
   |
SupabaseClient (supabase-kt: Auth, Postgrest, Storage)
   |  HTTPS
Supabase: Auth · Postgres (tables + RLS + rpc functions) · Storage
```

- The Kotlin services hold the spec's flow logic, guards and error replies. RLS and `security definer` SQL functions enforce the same rules on the server, so a modified client still can't break ARCH §8.
- Each service returns a `Result`-style type whose failures are exactly the five spec replies: `accessDenied`, `promptLocation`, `listingUnavailable`, `rejectChange(reason)`, `rejectAgreement(reason)`.

### 2.1 Package layout (`com.example.dwelly`)

```
model/        entities (User, Landlord, Tenant, Admin, Listing, ListingPhoto, ListingStats,
              Enquiry, Agreement, ForumThread, ForumPost) + all spec enums + DwellyError
data/         SupabaseProvider, DTOs (@Serializable), table/rpc access per service
service/      DwellyService, ListingService, DiscoveryService, AgreementService, ForumService
ui/navigation role-based NavHost
ui/auth  ui/landlord  ui/tenant  ui/forum  ui/agreement  ui/admin  ui/theme
```

### 2.2 Supabase project layout (repo root)

```
supabase/
  migrations/   0001_schema.sql, 0002_auth.sql, 0003_forum.sql (A) · 0004_listings.sql (B) · 0005_agreements.sql (C)
  seed.sql      seeded Landlord + ACTIVE dummy listings
  tests/        pgTAP tests for RLS
```

Managed with the Supabase CLI (`npx supabase link`, `npx supabase db push`, `npx supabase test db`).

### 2.3 Build changes (`gradle/libs.versions.toml`, `app/build.gradle.kts`)

- `supabase-kt` BOM with `auth-kt`, `postgrest-kt` and `storage-kt`, plus `ktor-client-okhttp`. Pin the current stable versions when adding them.
- The Kotlin serialization plugin `org.jetbrains.kotlin.plugin.serialization` at `version.ref = "kotlin"`. AGP 9 has built-in Kotlin, so don't add `kotlin-android`.
- `navigation-compose`, `lifecycle-viewmodel-compose`, `coil-compose` (photos) and `play-services-location` (current location).
- `buildFeatures { buildConfig = true }`. Read `SUPABASE_URL` and `SUPABASE_ANON_KEY` from `local.properties` (not checked in) into `BuildConfig`. The service-role key never goes into the app.
- Manifest: `INTERNET`, `ACCESS_COARSE_LOCATION`. Manual location entry is the fallback when permission is denied.

## 3. Team split

Three people, three domains. Each person owns the Kotlin service, SQL migrations/RPCs/RLS and screens for their domain.

| | Person A — Platform, Auth & Community | Person B — Landlord & Listings | Person C — Tenant Discovery & Agreements |
|---|---|---|---|
| Services | `DwellyService` (facade), `ForumService` | `ListingService` | `DiscoveryService`, `AgreementService` |
| Tables | `users`, `forum_threads`, `forum_posts`, `post_reactions`, `post_reports` | `listings`, `listing_photos`, `listing_stats`, `listing_views`, `listing_saves`, `enquiries` | `agreements` |
| DFD processes | 1.0 Authenticate & Authorize, 5.0 Run Community Forum | 2.0 Manage Listings (2.1–2.5) | 3.0 Discover Listings, 4.0 Manage Rental Agreements |
| Screens | Login, Register, role routing, Forum (threads, posts, replies, reactions, report), Admin moderation | Landlord dashboard, Create/Edit listing, photo upload, status actions, stats, completeness + suggestions | Set location, Feed (sort/filter), Listing detail, save/enquire, Tenant Home, Agreement create/edit/send (landlord side) and view/sign/download (tenant side) |

### 3.1 Person A — Platform, Auth & Community

**Phase 0 (blocking, done first):**
1. Create the Supabase project. Add the build dependencies from §2.3, and `SupabaseProvider`.
2. Write `0001_schema.sql` for **all 13 tables** from ARCH §6, as the shared contract. Use Postgres enums for every spec enum, plus `DELETED` (D3). `listings.amenities` and `listings.rules` are `jsonb`, as is `agreements.terms`. Add `UNIQUE(agreements.listing_id)` and a unique `forum_threads.listing_id` (one thread per listing). Insert one general thread with `listing_id NULL`.
3. Write the `model/` package (entities and enums) and the `DwellyService` interface with every operation from ARCH §4 stubbed. B and C fill in their parts.
4. Helper SQL: `current_user_role()` (`security definer`, reads `users.role`; not `current_role`, which is a reserved SQL keyword) and `is_admin()`. `0002_auth.sql` turns RLS on for every table, so B and C tables deny all access until their owners add policies.

**Auth (process 1.0):**
- `register` / `login` / `logout` through Supabase Auth. A trigger on `auth.users` creates the `public.users` row. Only TENANT or LANDLORD can be picked at sign-up. ADMIN is set by hand in the Supabase dashboard.
- RLS on `users`: each user reads and updates only their own row, and the `role` column can't be updated. A view `user_names(user_id, name)` exposes names only, so forum authors are shown without contact details (ARCH §8.3).
- Role-based NavHost: LANDLORD → Landlord dashboard; TENANT → Tenant dashboard (Home / Discovery / Forum / Agreement tabs); ADMIN → moderation.

**Forum (process 5.0, LOGIC §4.7):**
- `create_thread(listing_id)` SQL function, called by B's `create_listing` in the same transaction. Kotlin `ForumService.createThread(listingId)` wraps the same function.
- `getThreads`: the general thread plus threads whose listing is ACTIVE. `post` (with an optional `parent_post_id` reply), `react`, `report`: TENANT only. `removePost`: ADMIN only. It sets `removed = true`, and removed posts are hidden.
- RLS: forum tables are readable by TENANT and ADMIN, and writable as above. Landlords have no forum access.

### 3.2 Person B — Landlord & Listings

**ListingService (process 2.0):**
- `create_listing(...)` RPC (LOGIC §4.1, messages 2–5) runs in one transaction: role check → insert listing (`status = ACTIVE`, `went_live_at = now()`) → insert `listing_stats(0,0,0)` → `create_thread(listing_id)`.
- `update` / `changeStatus` follow the LOGIC §2 state table exactly. Disallowed transitions return `rejectChange(reason)` and write nothing. Edit is allowed in any state except DELETED. Delete is a soft delete (D3).
- `record_stat(listing_id, metric)` RPC (`security definer`): VIEW upserts `listing_views.last_viewed_at`; SAVE/unsave inserts or deletes a `listing_saves` row; ENQUIRY inserts the enquiry. In each case the matching counter changes by ±1. C's code is the only caller.
- `score()` (completeness, LOGIC §5): six items (description, photos, property details, price fields, amenities, rules) → `filled ÷ 6 × 100`, plus one suggestion per missing item. Pure Kotlin, unit-tested.
- RLS on `listings`: insert and update only when `role = LANDLORD AND landlord_id = auth.uid()`. Select is allowed for the owner, for TENANT when `status = ACTIVE`, or for the tenant named on a SENT/SIGNED agreement for that listing. Another landlord can never see the row. `listing_stats` and `enquiries` are readable only by the listing owner, and are written only through the RPCs.
- Storage: bucket `listing-photos`, object path `{landlord_id}/{listing_id}/{position}.jpg`. Write policy: the path's first segment = `auth.uid()`. `listing_photos` holds `url` and `position`.

**Screens:** the Landlord dashboard (`getMyListings`: own listings except DELETED, each with status, views/saves/enquiries, completeness score and suggestions); a Create/Edit listing form with fields for every `listings` column, multi-select chips for the fixed Amenity and HouseRule lists, and a photo picker; and status actions (pause, resume, mark rented, delete).

### 3.3 Person C — Tenant Discovery & Agreements

**DiscoveryService (process 3.0, LOGIC §4.2–4.3, 4.6):**
- Location: get it from the device or manual entry and save it with `DwellyService.setLocation` (implemented by A, because only process 1.0 writes D1). Read it from the current `Tenant`.
- `feed(location, sort)`: no location → `promptLocation`. Otherwise load ACTIVE listings and order them by LATEST, PRICE_LOW_HIGH, PRICE_HIGH_LOW or NEAREST (haversine distance in Kotlin). Filters use the listing fields (rent range, property type, furnishing, bedrooms).
- `detail(listingId)`: visibility check (ACTIVE, or the RESIDENT's own rented property), else `listingUnavailable`. Then `ListingService.recordStat(VIEW)` and return every field the landlord provided. `saveListing` and `sendEnquiry` use the same check and go through B's `record_stat`.
- `getHome`: derive TenantMode (D2). RESIDENT → their own property only. SEEKER → recently viewed (ACTIVE only, latest first) + popular. Popular comes from a `popular_listings()` RPC that orders ACTIVE listings by `views + saves + enquiries` (stats aren't directly readable by tenants).

**AgreementService (process 4.0, LOGIC §4.5):**
- RPCs, each with its guards from LOGIC §3/§4.5, returning `rejectAgreement(reason)` or `accessDenied`:
  - `create_agreement(listing_id, tenant_id)`: the caller owns the listing, the listing is RENTED, and it has no agreement yet → DRAFT, with `terms` prefilled from the listing (rent, deposit, maintenance, lease, house rules).
  - `edit_agreement`: landlord only, DRAFT only.
  - `send_agreement`: DRAFT, and all 12 required clauses are filled → SENT.
  - `sign_agreement`: the caller is the landlord or the named tenant, the agreement is SENT, and the caller hasn't signed → set the caller's `*_signed_at`. Both set → SIGNED (the invariant is checked in SQL).
- RLS: readable by the landlord, and by the tenant only when SENT or SIGNED. All writes go through the RPCs.
- Screens: landlord-side create/edit/send, reached from a RENTED listing on B's dashboard (picking the tenant by email). Tenant-side view, sign and **download** (a local PDF made with `android.graphics.pdf.PdfDocument`, saved through the system file picker).

## 4. Integration contracts between people

| Producer | Consumer | Contract |
|---|---|---|
| A | B, C | `0001_schema.sql`, `model/`, the `DwellyService` interface, `current_user_role()` — frozen at the end of Phase 0. Changes go through A |
| A | B | `create_thread(listing_id)` SQL function, called inside `create_listing` |
| B | C | `ListingService.recordStat(metric, listingId)` / `record_stat` RPC. Listing DTOs and `listing_photos` URLs for feed and detail |
| B | C | Dashboard entry point "Create agreement" on RENTED listings, which opens C's agreement screen |
| C | A | The TenantMode derivation used by the Tenant dashboard's Home tab |

## 5. Phases

| Phase | Goal | A | B | C |
|---|---|---|---|---|
| 0 Foundation | Shared contract | Supabase project, schema, models, `DwellyService` stubs, build deps | Review the schema, draft the listing form fields | Review the schema, draft the agreement `terms` JSON shape |
| 1 Vertical slices | Each domain works end to end | Register/login, role routing, users RLS | `create_listing`, Landlord dashboard, listings RLS, photo upload | Location, feed with sorting, detail with VIEW stat |
| 2 Full domains | Every spec flow done | Forum + admin moderation, forum RLS | Edit/status machine, completeness, save/enquiry RPCs, `seed.sql` | Home (RESIDENT/SEEKER), save/enquire UI, Agreement create → send → sign → download |
| 3 Hardening | Spec conformance | pgTAP RLS tests across all tables (with B and C) | Cross-landlord isolation tests | Agreement invariant tests |

## 6. Testing

- **JVM unit tests** (`./gradlew :app:testDebugUnitTest`): the listing state machine guards, the completeness score, feed ordering and haversine distance, TenantMode derivation, and agreement transitions. The services depend on a data interface, so tests use fakes and don't call Supabase.
- **Database tests** (`npx supabase test db`, pgTAP), checked against ARCH §8:
  - Landlord X can't read landlord Y's listings, stats or agreements.
  - A tenant can't insert or update listings.
  - A tenant can't read a DRAFT agreement.
  - A non-admin can't remove a post.
  - Forum queries never return email or phone.
- **Manual acceptance:** walk through LOGIC §4.1–4.8 against the seeded data, once as each role.

## 7. Definition of done

- Every operation listed for `DwellyService` in ARCH §4 is implemented and reachable from the UI of the role allowed to use it.
- Only the five spec error replies are surfaced, and rejected requests leave the data unchanged.
- All ARCH §8 rules are enforced by RLS or RPCs, not only in the client.
- Nothing from ARCH §9 or LOGIC §7 has been built.
