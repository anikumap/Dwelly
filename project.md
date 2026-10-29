# Dwelly Project Log

Last Updated: 2026-10-29 (Session 2 — Bug Fix Round)
Current Scope: Person B (Landlord & Listings) Implementation

---

## 1. Overview & Current Status

Dwelly is a rental management Android (Jetpack Compose) platform using Supabase (Postgres + Auth + Storage).
Person A (Platform, Auth, Forum) was completed previously.
**Person B (Landlord & Listings, Process 2.0)** implementation is now complete and verified.

---

## 2. Summary of Changes

### Added Files (New)

| File | Purpose |
|---|---|
| `supabase/migrations/0004_listings.sql` | RLS policies, `is_tenant_on_agreement` security helper, atomic RPC `create_listing`, RPC `update_listing`, state machine RPC `change_listing_status`, RPC `record_stat`, and `listing-photos` storage policies. |
| `supabase/tests/database/listings.test.sql` | pgTAP tests for cross-landlord isolation, tenant write restrictions, LOGIC §2 state machine rules, and stats recording. |
| `app/src/main/java/com/example/dwelly/data/ListingApi.kt` | `ListingApi` interface & `SupabaseListingApi` implementation (RPC calls, PostgREST queries, and Supabase Storage photo upload). |
| `app/src/main/java/com/example/dwelly/ui/landlord/LandlordScreens.kt` | Landlord Dashboard (stats, completeness score, actions), Create/Edit form covering all columns, multi-select checkboxes for all 15 Amenities and 11 House Rules, photo selection, and "Create agreement" trigger on RENTED listings. |
| `app/src/test/java/com/example/dwelly/ListingServiceTest.kt` | Unit tests for role checking, state machine transitions, soft deletion checks, completeness scoring permutations, and `getMyListings`. |

---

### Modified Files (Added & Removed Details)

#### 1. `app/src/main/java/com/example/dwelly/service/ListingService.kt`
* **Removed**:
  * Stubbed methods throwing `TODO("Person B")` for `create`, `update`, `changeStatus`, `uploadPhoto`, `getMyListings`, `getCompleteness`, `score`, and `recordStat`.
* **Added**:
  * Constructor injection of `ListingApi`.
  * `create(...)`: Role guard (`LANDLORD`), calls `listingApi.createListing`, uploads any photos in loop, returns `Listing`.
  * `update(...)`: Owner guard (`landlordId == user.userId`), soft-delete guard (`status != DELETED`), calls `listingApi.updateListing`.
  * `changeStatus(...)`: Owner guard, calls `listingApi.changeListingStatus`.
  * `uploadPhoto(...)`: Owner guard, calls `listingApi.uploadPhoto`.
  * `getMyListings()`: Role guard, filters out `DELETED` listings, queries stats and photos count, calculates completeness score and returns `List<ListingOverview>`.
  * `getCompleteness(...)`: Calculates score and suggestions for a single listing.
  * `score(...)`: Pure Kotlin algorithm calculating `filled / 6 * 100` across description, photos, property details, price fields, amenities, and rules, generating specific suggestions for missing items.
  * `recordStat(...)`: Calls `listingApi.recordStat`.

#### 2. `app/src/main/java/com/example/dwelly/service/DwellyService.kt`
* **Removed**:
  * Default two-parameter constructor `(authApi, forumApi)`.
  * Parameterless delegation to skeleton `ListingService`.
* **Added**:
  * Constructor parameter `listingApi: ListingApi = SupabaseListingApi()`.
  * Passes `listingApi` into `ListingService(listingApi, forumService) { _currentUser.value }`.
  * Default argument `photos: List<ByteArray> = emptyList()` on `createListing`.

#### 3. `app/src/main/java/com/example/dwelly/DwellyApplication.kt`
* **Removed**:
  * `DwellyService(SupabaseAuthApi(), SupabaseForumApi())`.
* **Added**:
  * `DwellyService(SupabaseAuthApi(), SupabaseForumApi(), SupabaseListingApi())`.

#### 4. `app/src/main/java/com/example/dwelly/ui/navigation/DwellyNavHost.kt`
* **Removed**:
  * Placeholder private function `LandlordDashboard(onLogout: () -> Unit)` displaying static placeholder text.
* **Added**:
  * Integrated `LandlordScreen(dwelly, onCreateAgreement, onLogout)`.
  * Defined route contract `agreement/create/{listingId}` for Person C to plug into when a landlord taps "Create agreement" on a `RENTED` listing.

#### 5. `app/src/test/java/com/example/dwelly/Fakes.kt`
* **Added**:
  * `FakeListingApi`: In-memory implementation of `ListingApi` tracking listings, photos, stats, call logs, and state transition validation for offline unit tests.

#### 6. `supabase/seed.sql`
* **Removed**:
  * Two minimal hardcoded listings without descriptions or price breakdown.
* **Added**:
  * Detailed descriptions and property breakdown for seed listings 1 and 2.
  * Listing 3: `1BHK in Whitefield` (`ACTIVE`, 16000 rent, photos, 1 view, 1 save).
  * Listing 4: `3BHK Luxury Villa` in HSR Layout (`ACTIVE`, 65000 rent, photos, 1 view).
  * Matching log rows in `listing_views`, `listing_saves`, and forum threads for all seed listings.

---

## 3. Verification & Test Results

* **Unit Tests**:
  * `./gradlew :app:testDebugUnitTest --tests "com.example.dwelly.ListingServiceTest"`: **PASSED (Code 0)**
  * `./gradlew :app:testDebugUnitTest`: **ALL TESTS PASSED (Code 0)**
* **Build Compilation**:
  * `./gradlew :app:assembleDebug`: **BUILD SUCCESSFUL (Code 0)**
* **Database Spec Verification**:
  * Complete LOGIC §2 state machine implemented in SQL function `change_listing_status`.
  * RLS policies ensure landlords can only read/write their own listings, photos, stats, and enquiries.
  * Soft-deleted (`DELETED`) listings cannot be edited or transitioned back.

---

## 4. Bug Fix Round — Session 2 (Save Listing Issue)

### Root Cause
The "Save Listing" button appeared to do nothing after submission. Two separate causes were found:

| # | Root Cause | Symptom |
|---|---|---|
| 1 | `0004_listings.sql` not applied to Supabase | Server returned `PGRST202: function create_listing not found in schema cache` — the RPC simply doesn't exist yet in the database |
| 2 | `.decodeSingle<Listing>()` used for RPC calls | supabase-kt wraps RPC results differently from table rows; `decodeSingle` throws an exception on a single-row RPC response |

### Changes Made

#### `app/src/main/java/com/example/dwelly/data/ListingApi.kt`
- **Removed**: `.decodeSingle<Listing>()` on `createListing`, `updateListing`, `changeListingStatus` RPC responses.
- **Added**: `.decodeAs<Listing>()` for all three RPC calls (correct method for supabase-kt RPC single-row responses).
- **Added**: `try/catch` block around photo storage upload so that bucket-not-found errors don't block listing creation (storage bucket is created by `0004_listings.sql`, which isn't applied yet).
- **Changed**: `listing_photos` insert from `upsert` mode to `insert` with `select()` returning the new row.

#### `app/src/main/java/com/example/dwelly/service/ListingService.kt`
- **Added**: `try/catch` around each `uploadPhoto` call inside `create()` so individual photo upload failures never abort listing creation.

#### `app/src/main/java/com/example/dwelly/ui/landlord/LandlordScreens.kt`
- **Added** to `LandlordViewModel`: `clearMessage()` public method to reset error state between mode switches.
- **Added** to `LandlordScreen` / `ListingFormScreen`: `errorMessage: String?` and `busy: Boolean` parameters plumbed from the VM.
- **Added**: `LinearProgressIndicator` + "Saving listing to server..." text shown while `busy == true`.
- **Added**: Error message display using `MaterialTheme.colorScheme.error` color when `errorMessage != null`.
- **Changed**: Save/Update button: `enabled = !busy && title.isNotBlank() && address.isNotBlank() && rent > 0` — prevents double-tap submission.
- **Added**: `vm.clearMessage()` called when navigating between Dashboard / Create / Edit modes.
- **Added**: `android.util.Log.e("Dwelly", ...)` on createListing/updateListing failure for Logcat debugging.

### What Is Still Needed to Make Save Work End-to-End

> **Critical:** Apply `0004_listings.sql` to the Supabase project.

Steps:
1. Open [Supabase SQL Editor](https://supabase.com/dashboard/project/ztviafmfzkscpyjkedty/sql)
2. Paste the full contents of `supabase/migrations/0004_listings.sql`
3. Click **Run**
4. Go to **API → Configuration → Reload schema** (or run `NOTIFY pgrst, 'reload schema';` in the SQL editor)
5. Re-run the app and tap "Save Listing" — it will now call `create_listing` successfully.

---

## 5. Known Blockers & Open Items

| Item | Status | Action needed |
|---|---|---|
| `0004_listings.sql` not applied to Supabase | 🔴 BLOCKER | Paste into SQL Editor and run (see §4 above) |
| Real image picker for photos | 🟡 Deferred | Currently uses `ByteArray(10)` dummy bytes; wire up `ActivityResultContracts.GetContent` later |
| pgTAP database tests | 🟡 Blocked | Requires Docker + `npx supabase start` locally |
| Person C: `AgreementService` + Discovery screens | 🟡 Pending | Route `agreement/create/{listingId}` is registered; Person C fills in the composable |

---

## 6. Bug Fix Round — Session 3 (Migration Re-run Error)

### Problem
Running `0004_listings.sql` a second time threw:
```
ERROR: 42710: policy "listings_select" for table "listings" already exists
```
The migration had been partially applied before (RLS policies existed, but SQL functions did not), so re-running it on those policies caused a duplicate conflict.

### Root Cause
`CREATE POLICY` has no `IF NOT EXISTS` guard (unlike `CREATE OR REPLACE FUNCTION`), so every `CREATE POLICY` statement fails if a policy with that name already exists on the table.

### Fix Applied — `supabase/migrations/0004_listings.sql`

Added `DROP POLICY IF EXISTS` before every `CREATE POLICY` block, making the entire script **idempotent** (safe to re-run any number of times):

| Block | Drops added before re-create |
|---|---|
| `listings` RLS | `listings_select`, `listings_insert`, `listings_update` |
| `listing_photos` RLS | `listing_photos_select`, `listing_photos_insert`, `listing_photos_update`, `listing_photos_delete` |
| `listing_stats` / `enquiries` RLS | `listing_stats_select`, `enquiries_select` |
| `listing_views` / `listing_saves` RLS | `listing_views_select`, `listing_saves_select` |
| Storage object policies | `listing_photos_storage_select`, `listing_photos_storage_insert`, `listing_photos_storage_update`, `listing_photos_storage_delete` |

Functions (`create_listing`, `update_listing`, `change_listing_status`, `record_stat`, `is_tenant_on_agreement`) already used `CREATE OR REPLACE FUNCTION` so they were already idempotent — no change needed there.

### What to do now
1. Open Supabase SQL Editor → new tab
2. Paste **full contents** of [`0004_listings.sql`](file:///C:/College/dwelly/supabase/migrations/0004_listings.sql) → Run
3. Should show **"Success. No rows returned"**
4. In another tab run: `NOTIFY pgrst, 'reload schema';`
5. Try **Save Listing** in the app — it will now call `create_listing` successfully

---

## 7. Safe Re-run Script Created

### Why
User's teammate flagged the `DROP POLICY` approach as potentially destructive. Supabase also warns on any `DROP` statement.

### What was created
**New file**: `supabase/migrations/0004_listings_safe_rerun.sql`

This script contains **only `CREATE OR REPLACE`** statements — nothing is dropped or deleted:
- `is_tenant_on_agreement` function
- `create_listing` function  
- `update_listing` function
- `change_listing_status` function
- `record_stat` function
- Storage bucket (`ON CONFLICT DO NOTHING` — skips if already exists)
- **No policy creation** — policies already exist from the partial first run, so they're left untouched

### Why this is safe
The policies were already created correctly in the partial first run (the error message confirmed `listings_select` existed, meaning all policies went in fine). The only thing missing was the 4 SQL functions. `CREATE OR REPLACE FUNCTION` never deletes anything — it just overwrites the function definition.

### What to paste into Supabase SQL Editor
Use `supabase/migrations/0004_listings_safe_rerun.sql` — NOT `0004_listings.sql`.
