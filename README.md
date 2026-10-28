# Dwelly

A rental management app for Android with two dashboards:

- **Landlords** manage their own property listings and the rental agreement for each rented property.
- **Tenants** discover listings near them, enquire, take part in community forum discussions, and see and sign their own agreement.
- An **Admin** moderates the forum.

Each role sees only what's relevant to it. The behaviour is defined in the spec: [`ARCHITECTURE(1).md`](<ARCHITECTURE(1).md>) and [`LOGIC.md`](LOGIC.md).

## Status

| Domain | What it covers | Status |
|---|---|---|
| Platform, Auth & Community | Supabase schema, sign-up/login, role-based navigation, community forum, Admin moderation | Done |
| Landlord & Listings | Listing create/edit/status, photos, stats, completeness score, Landlord dashboard | In progress |
| Tenant Discovery & Agreements | Location, feed with sorting and filters, listing detail, Tenant Home, rental agreements | In progress |

See [`plan.md`](plan.md) for the full plan, and [`jumpstart.md`](jumpstart.md) for the task list of each domain.

## Tech stack

- **Android:** Kotlin 2.2, Jetpack Compose (Material 3), Navigation Compose. `minSdk` 28, `targetSdk` 37.
- **Backend:** [Supabase](https://supabase.com):
  - Postgres for the data, with row-level security enforcing the access rules.
  - Auth for accounts.
  - Storage for listing photos.
  - The app talks to it through [supabase-kt](https://github.com/supabase-community/supabase-kt).
- **Build:** Gradle 9.6, Android Gradle Plugin 9.4 (built-in Kotlin).

## Getting started

1. **Create a Supabase project** at supabase.com, or ask a teammate for access to the shared one.
2. **Set up the database.** In the Supabase SQL Editor, run each file in `supabase/migrations/` in filename order. Optionally run `supabase/seed.sql` for test data.
3. **Connect the app.** Add your project's URL and anon key to `local.properties` in the project root. This file is git-ignored; never commit it.
   ```
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon key>
   ```
4. **For testing**, turn off **Confirm email** under Authentication → Sign In / Providers → Email.
5. **Run the app.** Open the project in Android Studio, let Gradle sync, and run the `app` configuration on an emulator or device.

With the seed loaded you can log in as `landlord@seed.dwelly` or `tenant@seed.dwelly` (password `dwelly123`). To make an Admin, register an account, then run in the SQL Editor:
```sql
update public.users set role = 'ADMIN' where email = '<email>';
```

[`jumpstart.md`](jumpstart.md) has a more detailed walkthrough and troubleshooting.

## Commands

If `java` is not on your PATH, set `JAVA_HOME` to Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`) first. On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

| Task | Command |
|---|---|
| Build | `./gradlew :app:assembleDebug` |
| Install on device | `./gradlew :app:installDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |
| Single test class | `./gradlew :app:testDebugUnitTest --tests "com.example.dwelly.ForumServiceTest"` |
| Lint | `./gradlew :app:lintDebug` |
| Database tests (needs Docker) | `npx supabase start`, then `npx supabase test db` |

## Project structure

```
app/src/main/java/com/example/dwelly/
  model/      entities and enums from the spec, and the five spec errors (DwellyError)
  data/       Supabase client and API classes behind interfaces
  service/    DwellyService (single entry point) and the domain services
  ui/         Compose screens: auth, forum, admin, navigation
supabase/
  migrations/ database schema, access rules and SQL functions
  seed.sql    minimal test data
  tests/      pgTAP database tests
```

Every request goes through `DwellyService`. Access rules are enforced in Postgres (row-level security and `security definer` functions), not only in the app.
