# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Dwelly is a rental-management Android app with Landlord and Tenant dashboards and an Admin who moderates the forum. All three domains are implemented and merged on `main`: platform, auth and forum (Person A), landlord listings (Person B), and tenant discovery and agreements (Person C). Every role's workflow has been tested end to end on an emulator. A separate, older `Dwelly` project sits next to this one in `AndroidStudioProjects/`. Both use the same Gradle root project name (`Dwelly`) and package (`com.example.dwelly`), so check which folder you are in before editing.

The original spec and planning documents were removed from the repo. Code comments still cite their sections (`LOGIC §4.3`, `ARCHITECTURE §8`), and the rules below are what those sections required.

## Rules the app follows

- **Names are fixed.** Keep the existing class, operation, enum and table names (`DwellyService`, `ListingService`, `DiscoveryService`, `AgreementService`, `ForumService`, `ListingStatus.ACTIVE`, …). Out of scope: payments, chat, notifications, map view, reviews, e-signature providers.
- `DwellyService` is the single entry point and delegates to the four services. Each data store is written only by its owning service: Discovery sends view, save and enquiry updates to `ListingService` and doesn't write listing data itself.
- Access rules: every Landlord query is filtered by `landlord_id` = caller. Tenants never create, edit or delete listings, and a Landlord has no Discovery. Landlord contact details never appear in the forum. An Agreement is visible only to its two parties, and only from SENT onward for the tenant. Only Admin removes forum posts.
- Error replies are limited to `accessDenied`, `promptLocation`, `listingUnavailable`, `rejectChange(reason)` and `rejectAgreement(reason)`. A rejected request changes nothing. Any other server or network failure becomes a `ServerError` with a short message, because supabase-kt's exception messages contain the request headers (the user's token).
- Listing state machine: ACTIVE ⇄ PAUSED, ACTIVE/PAUSED → RENTED, ACTIVE/PAUSED/RENTED → DELETED (a soft delete; DELETED is final). Edit is allowed in any state except DELETED.
- Agreement: DRAFT (landlord edits clauses) → SENT (all required clauses filled) → SIGNED (both parties signed). One per RENTED listing.
- Decisions already made on the spec's contradictions: TenantMode (RESIDENT/SEEKER) is derived from a SENT or SIGNED agreement and never stored; `DELETED` was added to `ListingStatus`; the per-metric tables (`listing_views`, `listing_saves`, `enquiries`) are the stats log; listing detail goes through `DiscoveryService.detail`, which sends the VIEW to `ListingService`.

## Commands

Run these from this directory. On PowerShell use `.\gradlew.bat`; in Git Bash use `./gradlew`.

| Task | Command |
|---|---|
| Debug build | `./gradlew :app:assembleDebug` |
| Install on a connected device or emulator | `./gradlew :app:installDebug` |
| Unit tests (JVM) | `./gradlew :app:testDebugUnitTest` |
| Single unit test | `./gradlew :app:testDebugUnitTest --tests "com.example.dwelly.ExampleUnitTest.addition_isCorrect"` |
| Instrumented tests (needs a device) | `./gradlew :app:connectedDebugAndroidTest` |
| Single instrumented test | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.dwelly.ExampleInstrumentedTest` |
| Lint | `./gradlew :app:lintDebug` |
| Apply migrations to the linked Supabase project | `npx supabase link --project-ref <ref>` once, then `npx supabase db push`, or run each file in `supabase/migrations/` in the SQL editor (they're safe to re-run) |

The project doesn't use Docker, and there are no local database tests. The database is the hosted Supabase project.

If `java` is not on PATH, set `JAVA_HOME` to Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`) before running Gradle.

The app reads `supabase.url` and `supabase.anonKey` from `local.properties` (not checked in) into `BuildConfig`. Without them it builds, but every server call fails with a "Supabase is not configured" message. Never put the service-role key in the app.

## Build setup

The toolchain is very new, so check APIs and DSL against these versions instead of older Android docs:

- Gradle 9.6 wrapper. The daemon runs on a JDK 25 toolchain, provisioned through foojay (`gradle/gradle-daemon-jvm.properties`). App code compiles to Java 11.
- AGP 9.4 with its built-in Kotlin support. There is no `org.jetbrains.kotlin.android` plugin; only the Compose compiler and serialization plugins (Kotlin 2.2.10) are applied. Don't add the standalone Kotlin Android plugin.
- Libraries are pinned to versions built with Kotlin 2.2 or earlier (supabase-kt 3.2.6, Ktor 3.3.1, Navigation 2.10.2, Coil 3.3.0), because Kotlin 2.2 can't read newer metadata. Lint's newer-version warnings for these are expected. Upgrading supabase-kt past 3.2.x means bumping `kotlin` in the catalog first.
- `compileSdk` uses the new `compileSdk { version = release(37) }` DSL. `targetSdk` is 37 and `minSdk` is 28.
- The release build uses AGP's `optimization { }` block instead of `isMinifyEnabled`/`proguardFiles`. R8 keep rules live in `app/src/main/keepRules/*.keep`, not `proguard-rules.pro`.
- Configuration cache is on (`gradle.properties`), so build logic must stay configuration-cache compatible.
- All dependency and plugin versions go through the version catalog in `gradle/libs.versions.toml`. Compose library versions come from the Compose BOM, so add Compose artifacts without a version.
- `settings.gradle.kts` sets `FAIL_ON_PROJECT_REPOS`, so add repositories there, not in module build files.

## App structure

Single module, `:app`, single activity. Layers, in package `com.example.dwelly`:

- `model/`: the entities and enums (shared contract). Table-backed entities are `@Serializable` with snake_case `@SerialName`s matching the columns, so they decode straight from Supabase rows.
- `data/`: `SupabaseProvider` (lazy client, `serverCall`), and the `AuthApi`, `ForumApi`, `ListingApi`, `DiscoveryApi` and `AgreementApi` interfaces with Supabase implementations. Services depend on these interfaces, so unit tests use the fakes in `app/src/test/.../Fakes.kt`.
- `service/`: `DwellyService` is the single entry point and holds the signed-in user (`currentUser: StateFlow<User?>`). It delegates to `ForumService`, `ListingService`, `DiscoveryService` and `AgreementService`. Services return `Result<T>` and never throw.
- `ui/`: Compose styled only through `ui/theme` (always dark, dynamic colour off): black background, deep-blue surfaces, cobalt blue as the main colour, and hot pink (`tertiary`/`error`) reserved for highlights such as errors, destructive actions, progress and the selected tab. Screens change looks only through attributes on existing composables (`style`, `color`, `colors`, padding), not new components. `DwellyNavHost` routes on the signed-in user's role and rebuilds the nav graph on login or logout. ViewModels are created with `viewModel { ... }`, taking the `DwellyService` from `DwellyApplication`.

Errors: the five spec replies are `DwellyError` subclasses. SQL functions raise them as the exception message (`'accessDenied'`, `'rejectChange: <reason>'`), and `serverCall { }` maps them back. Use this convention in new SQL functions.

Database (`supabase/`): migrations run in filename order: `0001_schema.sql` (all 13 tables), `0002_auth.sql` (role helpers, sign-up trigger, RLS on for every table, users policies), `0003_forum.sql`, `0004_listings.sql` (listing RPCs, photos and stats) and `0005_agreements.sql` (agreement RPCs, popular listings). `seed.sql` adds test data. Security is enforced in Postgres, and the Kotlin checks only fail fast:
- Every table has RLS on, so a table with no policy denies everything.
- The forum tables are reachable only through `security definer` functions (`get_threads`, `post_in_thread`, ...).
- The role check is `public.current_user_role()`. `current_role` is a reserved SQL keyword.
- Users sign up as LANDLORD or TENANT only, with metadata read by the `on_auth_user_created` trigger. An Admin is promoted by hand in the SQL editor.
- Every new table starts with `revoke all ... from anon, authenticated` (Supabase's defaults grant `authenticated` everything), then grants back only what clients need. New functions revoke EXECUTE from `public, anon`.

Test accounts on the shared project (password `dwelly123`): `landlord@seed.dwelly`, `tenant@seed.dwelly` and `admin@seed.dwelly`.
