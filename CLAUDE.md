# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Dwelly is a rental-management app with separate Landlord and Tenant dashboards (plus an Admin who moderates the forum). Person A's domain (Supabase schema, auth, role routing, forum, Admin moderation) is implemented. Person B's (listings) and Person C's (discovery, agreements) are skeletons whose methods are `TODO("Person B")` / `TODO("Person C")`, and their screens are placeholder `Text`s in `ui/navigation/DwellyNavHost.kt`. There is no README and no git repo yet. A separate, older `Dwelly` project sits next to this one in `AndroidStudioProjects/`. Both use the same Gradle root project name (`Dwelly`) and package (`com.example.dwelly`), so check which folder you are in before editing.

## Specs are the source of truth

Two spec files define the system. Read the relevant one before implementing any feature:

- `ARCHITECTURE(1).md` covers what the system is made of: actors, layers, component classes and their operations, enums, the 13-table data model, DFD processes, security rules, and out-of-scope items. `LOGIC.md` refers to it as `ARCHITECTURE.md`.
- `LOGIC.md` covers how the system behaves: the Listing and Agreement state machines, step-numbered flows (messages 1–18 match the UML diagrams), stats and completeness rules, and the only allowed error replies.

Hard constraints from the specs:

- **Names are fixed.** Use the class, operation, enum and table names exactly as written (`DwellyService`, `ListingService`, `DiscoveryService`, `AgreementService`, `ForumService`, `ListingStatus.ACTIVE`, …). Don't rename anything, and don't add features or steps. Leave out everything in the specs' out-of-scope and not-specified lists (payments, chat, notifications, map view, reviews, e-signature providers, …).
- `DwellyService` is the single entry point and delegates to the four services. Each data store is written only by its owning service (the DFD rule): Discovery sends view, save and enquiry updates to `ListingService` and doesn't write listing data itself.
- Access rules: every Landlord query is filtered by `landlord_id` = caller. Tenants never create, edit or delete listings. Landlord contact details never appear in the forum. An Agreement is visible only to its two parties, and only from SENT onward for the tenant. Only Admin removes forum posts.
- Error replies are limited to `accessDenied`, `promptLocation`, `listingUnavailable`, `rejectChange(reason)` and `rejectAgreement(reason)`. A rejected request changes nothing.

`plan.md` is the implementation plan: Supabase backend, the three-person domain split, phases, and the working decisions (D1–D6) on the contradictions below. Follow those decisions unless the user overrides them.

The specs contradict themselves (and this repo) in places:

- ARCHITECTURE asks for a web UI with a REST service layer and lists "mobile app" as out of scope, but this repo is an Android app.
- ARCHITECTURE says TenantMode (RESIDENT/SEEKER) is derived from whether an agreement exists and never stored. LOGIC §1 says the tenant chooses it at onboarding.
- LOGIC's listing state machine has a DELETED state, but the `ListingStatus` enum (ACTIVE, PAUSED, RENTED) and the `listings` table have no way to represent it.
- LOGIC §5 says `ListingStats.record` inserts a log row, but ARCHITECTURE says there is no activity-log table.
- ARCHITECTURE puts listing detail on `DiscoveryService.detail()`. LOGIC §4.3 puts it on `ListingService.getListingDetail()`.

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
| Apply migrations to the linked Supabase project | `npx supabase link --project-ref <ref>` once, then `npx supabase db push` |
| Database (pgTAP) tests, needs Docker | `npx supabase start`, then `npx supabase test db` |

If `java` is not on PATH, set `JAVA_HOME` to Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`) before running Gradle.

The app reads `supabase.url` and `supabase.anonKey` from `local.properties` (not checked in) into `BuildConfig`. Without them, it builds, but every server call fails with a "Supabase is not configured" message. Never put the service-role key in the app.

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

- `model/`: the spec entities and enums (shared contract, owned by Person A). Table-backed entities are `@Serializable` with snake_case `@SerialName`s matching the columns, so they decode straight from Supabase rows. `ListingStatus.DELETED` is plan decision D3.
- `data/`: `SupabaseProvider` (lazy client), and `AuthApi` / `ForumApi` interfaces with Supabase implementations. Services depend on these interfaces, so unit tests use the fakes in `app/src/test/.../Fakes.kt`.
- `service/`: `DwellyService` is the single entry point and holds the signed-in user (`currentUser: StateFlow<User?>`). It delegates to `ForumService`, `ListingService`, `DiscoveryService` and `AgreementService`. Services return `Result<T>` and never throw.
- `ui/`: plain default Compose (no custom styling, per the user). `DwellyNavHost` routes on the signed-in user's role and rebuilds the nav graph on login or logout. ViewModels are created with `viewModel { ... }`, taking the `DwellyService` from `DwellyApplication`.

Errors: the five spec replies are `DwellyError` subclasses. SQL functions raise them as the exception message (`'accessDenied'`, `'rejectChange: <reason>'`), and `serverCall { }` maps them back. Use this convention in new SQL functions.

Database (`supabase/`): migrations run in filename order: `0001_schema.sql` (all 13 tables), `0002_auth.sql` (role helpers, sign-up trigger, RLS on for every table, users policies), `0003_forum.sql`. B and C add `0004_*` / `0005_*`. Security is enforced in Postgres, and the Kotlin checks only fail fast:
- Every table has RLS on, so a table with no policy denies everything.
- The forum tables are reachable only through `security definer` functions (`get_threads`, `post_in_thread`, ...).
- The role check is `public.current_user_role()`. `current_role` is a reserved SQL keyword.
- Users sign up as LANDLORD or TENANT only, with metadata read by the `on_auth_user_created` trigger. An Admin is promoted by hand in the SQL editor.
