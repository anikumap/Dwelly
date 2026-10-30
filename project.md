# Dwelly Project Log

Last Updated: 2026-10-30
Current Scope: Person B (Landlord & Listings), PR #1 review fixes

---

## 1. Overview & Current Status

Dwelly is a rental management Android (Jetpack Compose) app backed by Supabase (Postgres + Auth + Storage).
Person A (Platform, Auth, Forum) is done. Person B (Landlord & Listings, Process 2.0) is in review on PR #1.

Verified on the PR branch: `./gradlew :app:assembleDebug`, `:app:testDebugUnitTest` (36 tests) and `:app:lintDebug` pass.
Not yet verified: `npx supabase test db` (pgTAP), which needs Docker.

---

## 2. Review Fixes

### Database (`supabase/migrations/0004_listings.sql`)

- `listings` is read-only for clients (`select` only, no insert or update policies). Every write goes through the
  `create_listing`, `update_listing` and `change_listing_status` security definer RPCs.
- `change_listing_status` follows the LOGIC §2 table exactly. A same-status request (PAUSED → PAUSED) is `rejectChange`.
  It and `update_listing` lock the row (`for update`) so two concurrent requests can't both pass the state check.
- The forum thread is created inside `create_listing` (`perform create_thread`), in the same transaction (plan.md).
  The Kotlin service doesn't call `ForumService.createThread` a second time.
- `record_stat` is TENANT only.
- `listing_photos`: clients can only `select` and `insert`. An insert must be by the listing's owner, while the listing
  isn't DELETED, and its `url` must point into the owner's own folder in the `listing-photos` bucket. So no stock or
  external image URLs, and no photos added to a deleted listing.
- The duplicate `0004_listings_safe_rerun.sql` is gone.

### Tests (`supabase/tests/database/listings.test.sql`)

- `plan(34)` matches the 34 assertions. The file ends with `finish(); rollback;`.
- Every query targets landlord 1's own listing by `landlord_id` rather than `limit 1`, so seed data can't change the result.
- New cases: direct listing insert and update refused, landlord can't `record_stat`, own-bucket photo accepted, external
  photo URL refused, photo row update refused, photos refused on a DELETED listing and on another landlord's listing.

### App

- Photos come from the system picker (`GetMultipleContents`). There's no dummy button, no Picsum fallback and no
  paste-a-URL option. Photos can be added on create and on edit.
- Upload failures are reported, never swallowed. If a photo fails during create, the listing is already saved, so the
  form switches to editing that listing instead of letting a retry create a second one.
- `LandlordViewModel` runs one request at a time through `runBusy`, which always clears `busy`. Status changes, create
  and edit all reload the list. Covered by `LandlordViewModelTest`.
- The form keeps amenities, rules and picked photos across rotation. Latitude, longitude and rent have no defaults and
  must be valid before Save is enabled.
- `getCompleteness` is limited to the owning landlord.
- Plain default Compose, with no custom shapes or colours.

---

## 3. Next Steps

- **Re-apply 0004** on the linked Supabase project. It was applied by hand earlier, so `db push` won't re-run it:
  run the file in the SQL editor, then `NOTIFY pgrst, 'reload schema';`.
- **Run pgTAP** with Docker: `npx supabase start`, then `npx supabase test db`.
- **Check with Person C**: the `CREATE_AGREEMENT` route is registered in `DwellyNavHost`. Confirm that they own the
  destination composable.
