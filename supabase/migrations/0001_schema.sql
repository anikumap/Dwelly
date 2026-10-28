-- Dwelly schema: the 13-table data model from ARCHITECTURE §6.
-- Shared contract for all three domains. Change it only through Person A.
--
-- Spec deviations (plan.md §1.2):
--   D3  listing_status has DELETED (soft delete, rows are never removed).
--   D6  passwords live in auth.users; public.users has no password_hash column.
--
-- Error convention for every SQL function: raise exception with the spec reply
-- as the message, optionally followed by ': <reason>', e.g.
--   raise exception 'accessDenied';
--   raise exception 'rejectChange: listing is RENTED';
-- The app maps these messages to DwellyError.

-- Enumerations (ARCHITECTURE §5). TenantMode and SortOrder are never stored.
-- Amenity and HouseRule values are stored as JSON lists in listings.
create type public.role as enum ('LANDLORD', 'TENANT', 'ADMIN');
create type public.listing_status as enum ('ACTIVE', 'PAUSED', 'RENTED', 'DELETED');
create type public.agreement_status as enum ('DRAFT', 'SENT', 'SIGNED');
create type public.property_type as enum ('APARTMENT', 'INDEPENDENT_HOUSE', 'VILLA', 'STUDIO', 'PG_SHARED');
create type public.furnishing as enum ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED');
create type public.preferred_tenant as enum ('WORKING_PROFESSIONALS', 'FAMILIES', 'STUDENTS');
create type public.stat_metric as enum ('VIEW', 'SAVE', 'ENQUIRY');

-- D1 users ------------------------------------------------------------------

create table public.users (
    user_id        uuid primary key references auth.users (id) on delete cascade,
    name           text not null,
    email          text not null unique,
    phone          text,
    role           public.role not null,
    location_label text,
    location_lat   double precision,
    location_lng   double precision
);

-- D2 listings ---------------------------------------------------------------

create table public.listings (
    listing_id           uuid primary key default gen_random_uuid(),
    landlord_id          uuid not null references public.users (user_id),
    title                text not null,
    description          text,
    address              text not null,
    lat                  double precision not null,
    lng                  double precision not null,
    rent                 numeric(12, 2) not null check (rent >= 0),
    deposit              numeric(12, 2) check (deposit >= 0),
    maintenance          numeric(12, 2) check (maintenance >= 0),
    available_from       date,
    lease_months         integer check (lease_months > 0),
    property_type        public.property_type not null,
    bedrooms             integer check (bedrooms >= 0),
    bathrooms            integer check (bathrooms >= 0),
    balconies            integer check (balconies >= 0),
    carpet_area          numeric(10, 2) check (carpet_area > 0),
    floor_no             integer,
    furnishing           public.furnishing,
    parking_two_wheeler  integer not null default 0 check (parking_two_wheeler >= 0),
    parking_four_wheeler integer not null default 0 check (parking_four_wheeler >= 0),
    amenities            jsonb not null default '[]' check (jsonb_typeof(amenities) = 'array'),
    rules                jsonb not null default '[]' check (jsonb_typeof(rules) = 'array'),
    preferred_tenant     public.preferred_tenant,
    status               public.listing_status not null default 'ACTIVE',
    went_live_at         timestamptz,
    created_at           timestamptz not null default now()
);
create index listings_landlord_idx on public.listings (landlord_id);
create index listings_status_idx on public.listings (status);

create table public.listing_photos (
    photo_id   uuid primary key default gen_random_uuid(),
    listing_id uuid not null references public.listings (listing_id),
    url        text not null,
    position   integer not null check (position >= 0),
    unique (listing_id, position)
);

create table public.listing_stats (
    listing_id uuid primary key references public.listings (listing_id),
    views      integer not null default 0 check (views >= 0),
    saves      integer not null default 0 check (saves >= 0),
    enquiries  integer not null default 0 check (enquiries >= 0)
);

-- listing_views, listing_saves and enquiries are the stat log rows (D4).
create table public.listing_views (
    user_id        uuid not null references public.users (user_id),
    listing_id     uuid not null references public.listings (listing_id),
    last_viewed_at timestamptz not null default now(),
    primary key (user_id, listing_id)
);

create table public.listing_saves (
    user_id    uuid not null references public.users (user_id),
    listing_id uuid not null references public.listings (listing_id),
    primary key (user_id, listing_id)
);

create table public.enquiries (
    enquiry_id uuid primary key default gen_random_uuid(),
    listing_id uuid not null references public.listings (listing_id),
    tenant_id  uuid not null references public.users (user_id),
    message    text not null check (length(trim(message)) > 0),
    created_at timestamptz not null default now()
);
create index enquiries_listing_idx on public.enquiries (listing_id);

-- D3 agreements -------------------------------------------------------------

create table public.agreements (
    agreement_id       uuid primary key default gen_random_uuid(),
    listing_id         uuid not null unique references public.listings (listing_id),
    landlord_id        uuid not null references public.users (user_id),
    tenant_id          uuid not null references public.users (user_id),
    status             public.agreement_status not null default 'DRAFT',
    terms              jsonb not null default '{}' check (jsonb_typeof(terms) = 'object'),
    landlord_signed_at timestamptz,
    tenant_signed_at   timestamptz,
    created_at         timestamptz not null default now(),
    -- LOGIC §3 invariant: SIGNED <=> both signatures recorded.
    constraint agreements_signed_invariant check (
        (status = 'SIGNED') = (landlord_signed_at is not null and tenant_signed_at is not null)
    )
);
create index agreements_tenant_idx on public.agreements (tenant_id);

-- D4 forum ------------------------------------------------------------------

create table public.forum_threads (
    thread_id  uuid primary key default gen_random_uuid(),
    listing_id uuid unique references public.listings (listing_id), -- NULL = general thread
    created_at timestamptz not null default now()
);
-- Exactly one general thread.
create unique index forum_threads_one_general on public.forum_threads ((listing_id is null))
    where listing_id is null;

create table public.forum_posts (
    post_id        uuid primary key default gen_random_uuid(),
    thread_id      uuid not null references public.forum_threads (thread_id),
    author_id      uuid not null references public.users (user_id),
    parent_post_id uuid references public.forum_posts (post_id),
    body           text not null check (length(trim(body)) > 0),
    removed        boolean not null default false,
    -- clock_timestamp, not now(): keeps posts made in one transaction in order
    created_at     timestamptz not null default clock_timestamp()
);
create index forum_posts_thread_idx on public.forum_posts (thread_id, created_at);

create table public.post_reactions (
    post_id  uuid not null references public.forum_posts (post_id),
    user_id  uuid not null references public.users (user_id),
    reaction text not null check (length(reaction) between 1 and 32),
    primary key (post_id, user_id, reaction)
);

create table public.post_reports (
    report_id   uuid primary key default gen_random_uuid(),
    post_id     uuid not null references public.forum_posts (post_id),
    reporter_id uuid not null references public.users (user_id),
    created_at  timestamptz not null default now(),
    unique (post_id, reporter_id)
);

insert into public.forum_threads (listing_id) values (null);

-- The 13th "table" in ARCHITECTURE §6 is the dummy seed: ordinary listings rows
-- owned by a seeded Landlord, loaded by supabase/seed.sql (Person B).
