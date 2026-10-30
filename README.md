# Dwelly

A rental management app for Android with a dashboard for each role:

- **Landlords** list their properties, follow each listing's views, saves and enquiries, and draft, send and sign the rental agreement once a property is rented.
- **Tenants** find listings near them, save them and send enquiries, talk in the community forum, and sign and download their rental agreement.
- An **Admin** moderates the forum.

Each role sees only what's relevant to it, and the access rules are enforced in the database, not just in the app.

## Status

All three parts are built, merged and tested end to end on an emulator.

| Part | What it covers | Status |
|---|---|---|
| Platform, Auth & Community | Sign-up and login, role-based navigation, community forum, Admin moderation | Done |
| Landlord & Listings | Create, edit, pause, rent and delete listings; photos; location from the device; stats; completeness score and suggestions | Done |
| Tenant Discovery & Agreements | Location from the device or typed in; feed sorted by latest, price or distance; full listing detail; save and enquire; Home for residents and seekers; rental agreements with PDF download | Done |

Known gaps:

- Landlords see how many enquiries a listing has, but not who sent them or what they wrote.
- Rotating the phone while creating or editing a listing goes back to the dashboard and loses the form.

## Tech stack

- **Android:** Kotlin 2.2, Jetpack Compose (Material 3, a black, blue and pink theme), Navigation Compose, Coil for images, Play Services location. `minSdk` 28, `targetSdk` 37.
- **Backend:** [Supabase](https://supabase.com), accessed through [supabase-kt](https://github.com/supabase-community/supabase-kt):
  - Postgres for the data, with row-level security and `security definer` functions enforcing the access rules.
  - Auth for accounts.
  - Storage for listing photos.
- **Build:** Gradle 9.6, Android Gradle Plugin 9.4 (built-in Kotlin).

No Docker or local database is needed. The app talks to the hosted Supabase project.

## Getting started

1. **Get a Supabase project.** Create one at supabase.com, or ask a teammate for access to the shared one.
2. **Set up the database.** In the Supabase SQL Editor, run each file in `supabase/migrations/` in filename order (`0001` to `0005`). The files are safe to run again. Optionally run `supabase/seed.sql` for test data.
3. **Connect the app.** Add your project's URL and anon key to `local.properties` in the project root. This file is git-ignored, so never commit it, and never put the service-role key in the app.
   ```
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon key>
   ```
4. **For testing**, turn off **Confirm email** under Authentication → Sign In / Providers → Email, so new accounts can log in straight away.
5. **Run the app.** Open the project in Android Studio, let Gradle sync, and run the `app` configuration on an emulator or device.

With the seed loaded, you can log in as `landlord@seed.dwelly` or `tenant@seed.dwelly` (password `dwelly123`). To make an Admin, register an account, then run this in the SQL Editor:
```sql
update public.users set role = 'ADMIN' where email = '<email>';
```

## Commands

If `java` is not on your PATH, set `JAVA_HOME` to Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`) first. On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

| Task | Command |
|---|---|
| Build | `./gradlew :app:assembleDebug` |
| Install on device or emulator | `./gradlew :app:installDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |
| Single test class | `./gradlew :app:testDebugUnitTest --tests "com.example.dwelly.DiscoveryServiceTest"` |
| Lint | `./gradlew :app:lintDebug` |

## Project structure

```
app/src/main/java/com/example/dwelly/
  model/      entities, enums and the five error replies (DwellyError)
  data/       Supabase client and the API classes behind interfaces
  service/    DwellyService (single entry point) and the listing, discovery,
              agreement and forum services
  ui/         Compose screens: auth, landlord, tenant, agreement, forum,
              admin, navigation, location helper and theme
app/src/test/ unit tests, with fake APIs in Fakes.kt
supabase/
  migrations/ database schema, access rules and SQL functions
  seed.sql    test data
```

Every request goes through `DwellyService`. Access rules are enforced in Postgres, and the app's own checks only fail fast.
