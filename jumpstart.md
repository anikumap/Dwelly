# Dwelly — Jumpstart for Person B and Person C

Person A (platform, auth, forum) is done. This file covers:

- what exists now
- how to get it running
- the rules your code must follow
- your task list

Read `plan.md` for the full plan, and `ARCHITECTURE(1).md` + `LOGIC.md` for the spec. **The spec is the source of truth: use its names exactly and add no features.**

---

## 1. Get it running (≈20 min)

1. **Supabase access.** We share one Supabase project. Ask Person A for:
   - an invite to the project (Organization → Team)
   - the **Project URL**
   - the **anon key**
2. **`local.properties`** (project root, never shared or committed). Add:
   ```
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon key>
   ```
3. **Database.** The project already has migrations `0001`–`0003` and `seed.sql` applied. If you set up your own project, run them in that order in the Supabase **SQL Editor**.
4. **Android Studio.** File → Open → the `Dwelly2` folder. Wait for Gradle sync, pick an emulator or phone, and press ▶ Run.
5. **Log in** with a seeded account (password `dwelly123`):
   - `landlord@seed.dwelly`: Landlord. You'll see a placeholder screen, which is Person B's job.
   - `tenant@seed.dwelly`: Tenant. This account is a RESIDENT because it has a SIGNED agreement. The Forum works; Home, Discover and Agreement are placeholders, which are Person C's job.
   - Admin: register any account, then run `update public.users set role = 'ADMIN' where email = '...';` in the SQL Editor.

**Command line.** If `java` is not found, set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr` first.

| Task | Command |
|---|---|
| Build | `./gradlew :app:assembleDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |
| One test class | `./gradlew :app:testDebugUnitTest --tests "com.example.dwelly.ForumServiceTest"` |
| Lint | `./gradlew :app:lintDebug` |
| Database tests (needs Docker) | `npx supabase start`, then `npx supabase test db` |

---

## 2. What Person A built

### Database (`supabase/`)

| File | Contents |
|---|---|
| `migrations/0001_schema.sql` | **All 13 tables** from ARCH §6, with your tables included and empty. Postgres enums for every spec enum. The single general forum thread. The `agreements` check that SIGNED ⇔ both signatures. |
| `migrations/0002_auth.sql` | `current_user_role()` and `is_admin()` helpers. The sign-up trigger that creates `public.users` (only LANDLORD/TENANT can be chosen). Users can read and update only their own row and can't change their role. A `user_names` view (id + name only). **RLS is switched on for every table.** |
| `migrations/0003_forum.sql` | The forum API: `create_thread`, `get_threads`, `get_posts`, `post_in_thread`, `react_to_post`, `report_post`, `remove_post`, `get_reported_posts`. |
| `seed.sql` | One landlord, one tenant, and a row in every table: an ACTIVE listing with a photo, stats, a view, a save and an enquiry; a RENTED listing with a SIGNED agreement; threads; and a post with a reaction and a report. |
| `tests/database/auth_forum.test.sql` | pgTAP tests for users and forum access. Use it as the template for yours. |

### App (`app/src/main/java/com/example/dwelly/`)

| Path | Contents |
|---|---|
| `model/` | Every spec entity and enum. `Listing`, `ListingPhoto`, `ListingStats`, `Enquiry` and `Agreement` are `@Serializable`, with column names already mapped, so they decode straight from Supabase. Also `FeedFilter`, `ListingOverview`, `ListingDetail`, `Completeness` and `TenantHome` for your results. |
| `model/DwellyError.kt` | The **only** five error replies: `AccessDenied`, `PromptLocation`, `ListingUnavailable`, `RejectChange(reason)`, `RejectAgreement(reason)`. |
| `data/` | `SupabaseProvider.client`, `serverCall { }` (turns server errors into `Result` failures), and `AuthApi` / `ForumApi`, the pattern to copy. |
| `service/DwellyService.kt` | The single entry point. **Every** spec operation is already declared here and delegates to your service. It also holds the signed-in user (`currentUser`), and `setLocation` (implemented by A, because only process 1.0 writes `users`). |
| `service/ListingService.kt` | **Person B's skeleton.** Every method is `TODO("Person B")`. |
| `service/DiscoveryService.kt`, `service/AgreementService.kt` | **Person C's skeletons.** Every method is `TODO("Person C")`. |
| `ui/navigation/DwellyNavHost.kt` | Routing by role. `LandlordDashboard` and the Tenant `HOME` / `DISCOVERY` / `AGREEMENT` tabs are placeholder `Text`s. Replace them with your screens. |
| `ui/auth`, `ui/forum`, `ui/admin` | A's screens. Use them as examples of the ViewModel and screen pattern. |

---

## 3. Rules for everyone

- **Spec names are fixed.** Classes, operations, enums and tables stay as they are in ARCH §4–§6. Don't build anything in ARCH §9 or LOGIC §7.
- **Security lives in Postgres.**
  - Kotlin checks only fail fast; the database must enforce every ARCH §8 rule by itself.
  - Your tables have RLS **on and no policies**, so the app can't read or write them until you add policies.
  - Also revoke `anon` access to them, e.g. `revoke all on public.listings from anon;`.
- **Multi-step writes go in one SQL function**, written `security definer` and `set search_path = ''`, then called from Kotlin with `client.postgrest.rpc(...)`. Copy the style of `0003_forum.sql`.
- **Error convention.**
  - SQL raises the spec reply as the message: `raise exception 'accessDenied';` or `raise exception 'rejectChange: listing is RENTED';`.
  - Wrap every Kotlin server call in `serverCall { }` and it becomes the matching `DwellyError`.
  - Services return `Result<T>` and never throw.
- **Role checks in SQL** use `public.current_user_role()`. Not `current_role`, which is a reserved SQL keyword.
- **Testable services.** Put Supabase calls behind an interface in `data/` (e.g. `ListingApi` + `SupabaseListingApi`), pass it in through `DwellyService`'s constructor and `DwellyApplication`, and unit-test with fakes. See `app/src/test/.../Fakes.kt`.
- **Migrations.**
  - Person B adds `0004_listings.sql` and Person C adds `0005_agreements.sql`.
  - Never edit `0001`–`0003` after they're applied. If you need a schema change, ask Person A.
- **UI is plain default Compose.** Material 3 components with no custom colors, spacing or styling. Match `ui/forum/ForumScreens.kt`.
- **Pinned versions.**
  - Kotlin is 2.2.10, so supabase-kt stays on 3.2.6, Ktor on 3.3.1 and Coil on 3.3.0. Newer versions need a newer Kotlin.
  - Lint will suggest upgrades. Ignore them unless the whole team agrees to bump Kotlin.
- **Working decisions D1–D6** (`plan.md` §1.2) are still waiting for confirmation. Build to them for now:
  - DELETED is a soft-delete status.
  - TenantMode is derived from agreements.
  - `listing_views`, `listing_saves` and `enquiries` are the stat log.
  - Discovery sends stat updates to `ListingService`.

---

## 4. Person B — Landlord & Listings

You own process 2.0, `ListingService`, and these tables: `listings`, `listing_photos`, `listing_stats`, `listing_views`, `listing_saves`, `enquiries`.

**SQL (`0004_listings.sql`)**
- [ ] **`create_listing(...)`** (LOGIC §4.1), all in one transaction:
  1. Check the caller is a LANDLORD.
  2. Insert the listing with `status = 'ACTIVE'`, `went_live_at = now()` and `landlord_id = auth.uid()`.
  3. Insert a `listing_stats` row at 0/0/0.
  4. `perform public.create_thread(listing_id)`. This is required: the forum depends on it.
- [ ] **`update_listing` / `change_listing_status`**: follow the LOGIC §2 state table exactly.
  - Raise `rejectChange: <reason>` for a disallowed transition.
  - Raise `accessDenied` when the caller isn't the owner.
  - DELETED is a soft delete. Edits are allowed in any state except DELETED.
- [ ] **`record_stat(listing_id, metric, ...)`**, `security definer`. Upsert `listing_views.last_viewed_at` / insert or delete a `listing_saves` row / insert the enquiry, then change the matching counter by ±1. Person C calls this; tenants never write these tables directly.
- [ ] **RLS on `listings`:**
  - Insert and update only when `current_user_role() = 'LANDLORD' and landlord_id = auth.uid()`.
  - Select is allowed for the owner, for a TENANT when `status = 'ACTIVE'`, or for the tenant named on a SENT/SIGNED agreement for that listing.
  - That last check needs C's `agreements` table, so wrap it in a small `security definer` helper function. This avoids RLS recursion and having to wait for C's policies.
- [ ] **RLS on `listing_photos`:** readable wherever the listing is readable; writable only by the owner.
- [ ] **`listing_stats` and `enquiries`:** readable only by the listing owner. Writes only through `record_stat`.
- [ ] **Storage bucket `listing-photos`.** Path `{landlord_id}/{listing_id}/{position}.jpg`. Write policy: the first path segment = `auth.uid()`. Store the URL in `listing_photos`.
- [ ] **pgTAP tests:** landlord X can't read landlord Y's listings or stats; a tenant can't insert or update listings; status transitions follow LOGIC §2.

**Kotlin**
- [ ] `ListingApi` + `SupabaseListingApi` in `data/`, and pass it into `ListingService` through `DwellyService` and `DwellyApplication`.
- [ ] Fill in every method in `ListingService`: `create`, `update`, `changeStatus`, `uploadPhoto`, `getMyListings`, `getCompleteness`, `score`, `recordStat`. You may refine the signatures. Update the delegating methods in `DwellyService` if you do.
- [ ] `score()` (LOGIC §5) covers six items: description, photos, property details, price fields, amenities, rules. It returns `filled ÷ 6 × 100` plus one suggestion per missing item. Pure Kotlin, so unit-test it.
- [ ] Unit tests for the status state machine guards and `score()`.

**Screens** (replace `LandlordDashboard` in `DwellyNavHost.kt`)
- [ ] Dashboard (`getMyListings`): own listings except DELETED, each with its status, views/saves/enquiries, completeness score and suggestions.
- [ ] Create/Edit listing form: every `listings` column, multi-select for the fixed `Amenity` and `HouseRule` lists, and a photo picker.
- [ ] Status actions: Pause, Resume, Mark rented, Delete.
- [ ] On RENTED listings, a **"Create agreement"** button that opens Person C's agreement screen. Agree the route with C.

**Seed:** `supabase/seed.sql` is yours to extend with more dummy ACTIVE listings. Keep the stat counters equal to the log rows.

---

## 5. Person C — Tenant Discovery & Agreements

You own processes 3.0 and 4.0, `DiscoveryService`, `AgreementService`, and the `agreements` table. Discovery **only reads** listing data; every write goes through `ListingService.recordStat` (Person B).

**Discovery** (LOGIC §4.2, §4.3, §4.6)
- [ ] **Location screen:**
  - Ask for coarse location. The `ACCESS_COARSE_LOCATION` permission and `play-services-location` are already set up.
  - Fall back to typing a place by hand.
  - Save it with `DwellyService.setLocation(Location(...))`, which is already implemented.
  - Read it from `dwelly.currentUser.value as Tenant`.
- [ ] **`feed(sort, filter)`:**
  - No location → `DwellyError.PromptLocation()`.
  - Otherwise load ACTIVE listings and sort them: LATEST, PRICE_LOW_HIGH, PRICE_HIGH_LOW, or NEAREST (haversine distance in Kotlin).
  - Apply `FeedFilter`.
- [ ] **`detail(listingId)`:**
  - The listing must be ACTIVE, or the RESIDENT's own rented property; otherwise `ListingUnavailable`.
  - Then `listingService.recordStat(VIEW, ...)`, and return the listing with its photos (`ListingDetail`).
- [ ] **`saveListing` / `sendEnquiry`:** same visibility check, then go through B's `recordStat`.
- [ ] **`getHome()`:**
  - Derive TenantMode here and never store it: RESIDENT if an agreement names this tenant with status SENT or SIGNED.
  - RESIDENT sees `TenantHome.Resident` (own property only).
  - SEEKER sees `TenantHome.Seeker` (recently viewed ACTIVE listings, latest first, plus popular ones).
- [ ] **`popular_listings()`:** a `security definer` RPC ordering ACTIVE listings by `views + saves + enquiries`. Tenants can't read `listing_stats` directly.

**Agreements** (LOGIC §3, §4.5), in `0005_agreements.sql`
- [ ] **`create_agreement(listing_id, tenant_id)`:** the caller owns the listing, it is RENTED, and it has no agreement yet → DRAFT, with `terms` prefilled from the listing.
- [ ] **`edit_agreement`:** landlord only, DRAFT only.
- [ ] **`send_agreement`:** all required clauses filled → SENT.
  - The required clauses are listed in LOGIC §4.5. Decide the `terms` JSON keys; `Agreement.terms` is a `Map<String, String>`.
- [ ] **`sign_agreement`:** the landlord or the named tenant; the agreement is SENT; the caller hasn't signed yet. Set the caller's `*_signed_at`. When both are set, change the status to SIGNED **in the same statement**, because the table's check constraint rejects SIGNED without both signatures.
- [ ] **Errors:** raise `rejectAgreement: <reason>` or `accessDenied`.
- [ ] **RLS:** the landlord reads their own agreements; the tenant reads theirs only when SENT or SIGNED. No direct writes.
- [ ] **pgTAP tests:** a tenant can't read a DRAFT; a non-party can't read anything; the signing order and the SIGNED invariant hold.

**Kotlin and screens**
- [ ] Fill in `DiscoveryService` and `AgreementService`, behind interfaces in `data/` so they can be unit-tested with fakes.
- [ ] Unit tests: feed ordering and haversine distance, TenantMode derivation, agreement transitions.
- [ ] Replace the Tenant `HOME`, `DISCOVERY` and `AGREEMENT` tabs in `DwellyNavHost.kt`.
- [ ] Discover: the feed with sort and filter, and the detail screen with Save and Enquire.
- [ ] Agreement, landlord side: create/edit/send, opened from B's "Create agreement" button. Pick the tenant by email: look up the user id in `users`; you may need a small `security definer` lookup function, because RLS hides other users' rows.
- [ ] Agreement, tenant side: view, sign, and **download** as a PDF (`android.graphics.pdf.PdfDocument`, saved through the system file picker).

---

## 6. Coordination

| Who | Needs from | What |
|---|---|---|
| B | A | `create_thread(listing_id)`, already available. Call it inside `create_listing`. |
| C | B | `record_stat` and `ListingService.recordStat`. Until B ships them, use a fake in your tests. |
| C | B | The listing select policy, so tenants can read ACTIVE listings. Until then, tenants see nothing. |
| B | C | The agreement screen route for the "Create agreement" button. |
| B, C | A | Any change to `0001_schema.sql`, `model/` or `DwellyService` signatures. |

---

## 7. Known gaps (be aware)

- **Not run yet:** the pgTAP tests (no Docker on A's machine), the seed's login section, and the app on a real device. All three should work but haven't been run. If you hit an error in any of them, report it to Person A.
- **Git:** clone the shared repo and work on your own branch (`person-b/...`, `person-c/...`). `local.properties` is ignored, so create your own copy (step 1.2).
- **`ARCHITECTURE(1).md` should be renamed to `ARCHITECTURE.md`**, because `LOGIC.md` refers to it by that name.
