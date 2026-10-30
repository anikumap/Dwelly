-- Minimal seed: one landlord, one tenant, one row or two in every table.
-- Listing 1 is ACTIVE (feed, forum); listing 2 is RENTED with a SIGNED
-- agreement, so the seeded tenant is a RESIDENT.
-- Log in as landlord@seed.dwelly or tenant@seed.dwelly, password dwelly123.
-- Test data only: never run this against production.
-- Runs as postgres (bypasses RLS). Run once, after the migrations.

-- D1: the sign-up trigger creates the public.users rows.
insert into auth.users (id, email, raw_user_meta_data) values
    ('00000000-0000-0000-0000-00000000000a', 'landlord@seed.dwelly', '{"name": "Seed Landlord", "role": "LANDLORD"}'),
    ('00000000-0000-0000-0000-00000000000b', 'tenant@seed.dwelly',   '{"name": "Seed Tenant", "role": "TENANT"}');

-- Logins. Supabase Auth needs a password hash, a confirmed email, an email
-- identity and empty (not NULL) token columns. This section can also be run
-- on its own on a database seeded by an earlier version of this file.
update auth.users set
    instance_id                = '00000000-0000-0000-0000-000000000000',
    aud                        = 'authenticated',
    role                       = 'authenticated',
    encrypted_password         = extensions.crypt('dwelly123', extensions.gen_salt('bf')),
    email_confirmed_at         = now(),
    created_at                 = coalesce(created_at, now()),
    updated_at                 = now(),
    raw_app_meta_data          = '{"provider": "email", "providers": ["email"]}',
    confirmation_token         = '',
    recovery_token             = '',
    email_change               = '',
    email_change_token_new     = '',
    email_change_token_current = '',
    phone_change_token         = '',
    reauthentication_token     = ''
where email like '%@seed.dwelly';

insert into auth.identities (id, user_id, provider_id, provider, identity_data, last_sign_in_at, created_at, updated_at)
select gen_random_uuid(), u.id, u.id::text, 'email',
       jsonb_build_object('sub', u.id::text, 'email', u.email, 'email_verified', true),
       now(), now(), now()
from auth.users u
where u.email like '%@seed.dwelly'
on conflict do nothing;

-- D2
insert into public.listings (listing_id, landlord_id, title, description, address, lat, lng, rent, deposit, maintenance, property_type, bedrooms, bathrooms, furnishing, status, went_live_at) values
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', '2BHK near Metro', 'Spacious 2BHK with balcony and park view', 'Indiranagar, Bengaluru', 12.9784, 77.6408, 30000, 60000, 2000, 'APARTMENT', 2, 2, 'SEMI_FURNISHED', 'ACTIVE', now()),
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'Studio in Koramangala', 'Cozy studio suitable for single professionals', 'Koramangala, Bengaluru', 12.9352, 77.6245, 18000, 36000, 1500, 'STUDIO', 1, 1, 'FULLY_FURNISHED', 'RENTED', now()),
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-00000000000a', '1BHK in Whitefield', 'Well-lit flat close to tech parks', 'Whitefield, Bengaluru', 12.9698, 77.7500, 16000, 32000, 1000, 'APARTMENT', 1, 1, 'SEMI_FURNISHED', 'ACTIVE', now()),
    ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-00000000000a', '3BHK Luxury Villa', 'Independent gated villa with private lawn', 'HSR Layout, Bengaluru', 12.9121, 77.6446, 65000, 150000, 5000, 'VILLA', 3, 3, 'FULLY_FURNISHED', 'ACTIVE', now());

insert into public.listing_photos (listing_id, url, position) values
    ('00000000-0000-0000-0000-000000000001', 'https://picsum.photos/seed/dwelly1/800/600', 0),
    ('00000000-0000-0000-0000-000000000003', 'https://picsum.photos/seed/dwelly3/800/600', 0),
    ('00000000-0000-0000-0000-000000000004', 'https://picsum.photos/seed/dwelly4/800/600', 0);

-- Counters match the log rows below.
insert into public.listing_stats (listing_id, views, saves, enquiries) values
    ('00000000-0000-0000-0000-000000000001', 1, 1, 1),
    ('00000000-0000-0000-0000-000000000002', 0, 0, 0),
    ('00000000-0000-0000-0000-000000000003', 1, 1, 0),
    ('00000000-0000-0000-0000-000000000004', 1, 0, 0);

insert into public.listing_views (user_id, listing_id) values
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000003'),
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000004');

insert into public.listing_saves (user_id, listing_id) values
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000003');

insert into public.enquiries (listing_id, tenant_id, message) values
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000b', 'Is parking available?');

-- D3
insert into public.agreements (listing_id, landlord_id, tenant_id, status, landlord_signed_at, tenant_signed_at) values
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000b', 'SIGNED', now(), now());

-- D4: one thread per listing (the general thread comes from 0001_schema.sql).
insert into public.forum_threads (thread_id, listing_id) values
    ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-0000000000f2', '00000000-0000-0000-0000-000000000002'),
    ('00000000-0000-0000-0000-0000000000f3', '00000000-0000-0000-0000-000000000003'),
    ('00000000-0000-0000-0000-0000000000f4', '00000000-0000-0000-0000-000000000004');

insert into public.forum_posts (post_id, thread_id, author_id, body) values
    ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000000b', 'How is the water supply here?');

insert into public.post_reactions (post_id, user_id, reaction) values
    ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000000b', 'LIKE');
insert into public.post_reports (post_id, reporter_id) values
    ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-00000000000b');
