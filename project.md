# Dwelly Project Log

Last Updated: 2026-10-30
Current Scope: Person B (Landlord & Listings) Implementation & Review Fixes

---

## 1. Overview & Current Status

Dwelly is a rental management Android (Jetpack Compose) platform using Supabase (Postgres + Auth + Storage).
Person A (Platform, Auth, Forum) was completed previously.
Person B (Landlord & Listings, Process 2.0) has resolved all review blockers and fully implemented photo upload, preview, and dashboard display.

---

## 2. Review Resolution & Fixes Applied

### Blockers Resolved

1. **Direct table mutation bypass on `listings`**:
   - `supabase/migrations/0004_listings.sql`: Revoked direct `INSERT` and `UPDATE` grants for authenticated users on `public.listings`.
   - Removed `listings_insert` and `listings_update` RLS policies. The `listings` table is now strictly read-only for clients (`SELECT` only).
   - All mutations must pass through `create_listing`, `update_listing`, and `change_listing_status` RPCs (security definer).

2. **Duplicate migration number**:
   - Deleted the temporary repair script `supabase/migrations/0004_listings_safe_rerun.sql`. Only canonical migrations remain (`0001` through `0004`).

3. **Dashboard UI freeze on status changes**:
   - In `LandlordScreens.kt`, `LandlordViewModel` previously called `load()` within `updateStatus` while `busy` was already true. Because `load()` returned immediately when `busy == true`, `busy` was never reset to `false`, freezing all UI actions.
   - Refactored `updateStatus`, `createListing`, and `updateListing` to reload data via an internal `reloadListingsInternal()` helper that executes within the existing coroutine and guarantees `busy = false` in a `finally` block.

4. **Real Photo Selection, Preview & Display**:
   - Replaced "Add dummy photo" button with a full photo management system:
     - **Device Storage Picker**: Android's `GetMultipleContents()` activity launcher. Picks multiple images from gallery/file manager. Respects the 10-photo limit (only takes as many as the remaining slots allow).
     - **Image URL Support**: Text input to paste valid image links (`http://`/`https://`). Validated before adding.
     - **Live Thumbnail Previews**: `AsyncImage` (Coil 3) thumbnails displayed in a horizontal scroll row as soon as photos are added — from device URIs and pasted URLs alike.
     - **Max 10 photos enforced**: Both picker and URL buttons are disabled once the limit is reached. An error-styled warning message appears at the limit.
     - **Remove button (✕)** on each new thumbnail to remove photos before saving.
     - **Edit mode**: Existing saved photos are shown in a read-only row above the add controls, so the landlord can see what's already uploaded.
     - **Dashboard card photo strip**: `ListingOverview` now includes `photos: List<ListingPhoto>`. `ListingOverviewCard` renders a horizontal `LazyRow` of thumbnails at the top of the card for any listing that has photos.
   - Removed the random Picsum fallback from `ListingApi.kt`.
   - `ListingService.create` no longer silently swallows photo upload errors.
   - Restored explicit `forumService.createThread(listingId)` call per LOGIC §4.1 message 5.

5. **pgTAP database test file syntax & isolation**:
   - `supabase/tests/database/listings.test.sql`: Updated assertion plan count from `plan(24)` to `plan(29)`.
   - Added tests ensuring landlords cannot insert or update the `listings` table directly via PostgREST.
   - Added tests ensuring landlords cannot call `record_stat`.
   - Replaced trailing `commit;` with `select * from finish(); rollback;` so tests clean up after themselves.

---

### Should Fix & Minor Issues Resolved

1. **`record_stat` caller validation**:
   - Updated `public.record_stat` RPC to require `public.current_user_role() = 'TENANT'`.
2. **Disallowed same-status transitions**:
   - `PAUSED→PAUSED` or `DELETED→DELETED` now raise `rejectChange` per LOGIC §2.
3. **Forum thread creation alignment**:
   - `ListingService.create()` explicitly calls `forumService.createThread(listingId)` per LOGIC §4.1.
4. **Service layer dependency cleanliness**:
   - `listingApi` is now a mandatory parameter in `DwellyService` (no default `SupabaseListingApi()`).
   - `DwellyServiceTest.kt` updated to pass `FakeListingApi()`.

---

## 3. Verification & Test Status

- **Unit Tests**: `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL (All unit tests pass)**
- **Android Compilation**: `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- **Git State**: Clean branch, no duplicate migration files.

---

## 4. Next Steps for Person B

- **Apply Updated 0004 Migration**: Run the updated `0004_listings.sql` in the Supabase SQL editor and execute `NOTIFY pgrst, 'reload schema';`
- **Check with Person C**: `CREATE_AGREEMENT` route is registered in `DwellyNavHost` — confirm with Person C that they own the destination composable.
- **Git commit author**: `git config user.email <github-email>` to link commits to your GitHub profile.

