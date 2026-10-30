-- Person B: access rules, status state machine, and stats for D2 (listings).
-- Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(34);

-- Fixtures (run as postgres) ------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
    ('11111111-0000-0000-0000-000000000001', 'landlord1@test.dev', '{"name": "Landlord One", "role": "LANDLORD"}'),
    ('11111111-0000-0000-0000-000000000002', 'landlord2@test.dev', '{"name": "Landlord Two", "role": "LANDLORD"}'),
    ('22222222-0000-0000-0000-000000000001', 'tenant1@test.dev',   '{"name": "Tenant One", "role": "TENANT"}'),
    ('22222222-0000-0000-0000-000000000002', 'tenant2@test.dev',   '{"name": "Tenant Two", "role": "TENANT"}');

-- Anonymous access denied ---------------------------------------------------
set local role anon;
select throws_ok('select * from public.listings', '42501', null, 'anon cannot read listings');
select throws_ok('select * from public.listing_stats', '42501', null, 'anon cannot read listing_stats');
reset role;

-- Landlord 1 creates listing ------------------------------------------------
set local role authenticated;
set local request.jwt.claims = '{"sub": "11111111-0000-0000-0000-000000000001", "role": "authenticated"}';

select lives_ok(
    $$select public.create_listing(
        'Sunny 2BHK', '123 MG Road', 12.9716, 77.5946, 25000, 'APARTMENT'::public.property_type,
        'Spacious apartment', 50000, 2000, '2026-11-01'::date, 11
    )$$,
    'landlord creates an active listing'
);

select is(
    (select count(*)::int from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'),
    1,
    'listing was created and belongs to landlord 1'
);

select is(
    (select status::text from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'),
    'ACTIVE',
    'created listing has status ACTIVE'
);

select is(
    (select views::int from public.listing_stats where listing_id = (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')),
    0,
    'listing_stats starts at 0 views'
);

-- Direct listing updates and inserts are refused for landlords
select throws_ok(
    $$update public.listings set status = 'ACTIVE'$$,
    '42501', null,
    'landlord cannot update listings table directly'
);

select throws_ok(
    $$insert into public.listings (landlord_id, title, address, lat, lng, rent, property_type)
      values ('11111111-0000-0000-0000-000000000001', 'Direct', 'Addr', 12.0, 77.0, 1000, 'APARTMENT')$$,
    '42501', null,
    'landlord cannot insert listings table directly'
);

-- Photos: only the owner's own uploads, never an external URL, never edited or removed
select lives_ok(
    $$insert into public.listing_photos (listing_id, url, position)
      values ((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'https://ref.supabase.co/storage/v1/object/public/listing-photos/11111111-0000-0000-0000-000000000001/' || (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')::text || '/0.jpg', 0)$$,
    'landlord adds a photo uploaded to their own folder'
);

select throws_ok(
    $$insert into public.listing_photos (listing_id, url, position)
      values ((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'https://picsum.photos/seed/x/800/600', 1)$$,
    '42501', null,
    'landlord cannot add an external image URL as a photo'
);

select throws_ok(
    $$update public.listing_photos set url = 'https://picsum.photos/seed/x/800/600'$$,
    '42501', null,
    'landlord cannot edit photo rows directly'
);

-- Landlord cannot record stats (only tenants can)
select throws_ok(
    $$select public.record_stat((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'VIEW'::public.stat_metric)$$,
    'P0001', 'accessDenied',
    'landlord cannot call record_stat'
);

-- Landlord 2 isolation ------------------------------------------------------
set local request.jwt.claims = '{"sub": "11111111-0000-0000-0000-000000000002", "role": "authenticated"}';

select is(
    (select count(*)::int from public.listings),
    0,
    'landlord 2 cannot see landlord 1 listings'
);

select is(
    (select count(*)::int from public.listing_stats),
    0,
    'landlord 2 cannot see landlord 1 stats'
);

select throws_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'PAUSED')$$,
    'P0001', 'accessDenied',
    'landlord 2 cannot change landlord 1 listing status'
);

-- Tenant permissions --------------------------------------------------------
set local request.jwt.claims = '{"sub": "22222222-0000-0000-0000-000000000001", "role": "authenticated"}';

select is(
    (select count(*)::int from public.listings
     where status = 'ACTIVE' and landlord_id = '11111111-0000-0000-0000-000000000001'),
    1,
    'tenant can read active listings'
);

select throws_ok(
    $$insert into public.listings (landlord_id, title, address, lat, lng, rent, property_type)
      values ('22222222-0000-0000-0000-000000000001', 'Illegal', 'Addr', 12.0, 77.0, 1000, 'APARTMENT')$$,
    '42501', null,
    'tenant cannot insert listings directly'
);

select throws_ok(
    $$select public.create_listing('Illegal', 'Addr', 12.0, 77.0, 1000, 'APARTMENT'::public.property_type)$$,
    'P0001', 'accessDenied',
    'tenant cannot call create_listing'
);

select is(
    (select count(*)::int from public.listing_stats),
    0,
    'tenant cannot read listing_stats directly'
);

-- Stats recording -----------------------------------------------------------
select lives_ok(
    $$select public.record_stat((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'VIEW'::public.stat_metric)$$,
    'tenant records a view'
);

select lives_ok(
    $$select public.record_stat((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'SAVE'::public.stat_metric, null, true)$$,
    'tenant records a save'
);

select lives_ok(
    $$select public.record_stat((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'ENQUIRY'::public.stat_metric, 'Is it available?')$$,
    'tenant sends an enquiry'
);

-- Verify counters incremented (as Landlord 1) -------------------------------
set local request.jwt.claims = '{"sub": "11111111-0000-0000-0000-000000000001", "role": "authenticated"}';

select is(
    (select views::int from public.listing_stats where listing_id = (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')),
    1,
    'stats recorded 1 view'
);

select is(
    (select saves::int from public.listing_stats where listing_id = (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')),
    1,
    'stats recorded 1 save'
);

select is(
    (select enquiries::int from public.listing_stats where listing_id = (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')),
    1,
    'stats recorded 1 enquiry'
);

-- State machine transitions (LOGIC §2) --------------------------------------
-- ACTIVE -> PAUSED
select lives_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'PAUSED')$$,
    'landlord pauses active listing'
);

-- Disallowed transition: same status PAUSED -> PAUSED
select throws_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'PAUSED')$$,
    'P0001', 'rejectChange: cannot transition from PAUSED to PAUSED',
    'cannot change to same status'
);

-- PAUSED -> ACTIVE (resume)
select lives_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'ACTIVE')$$,
    'landlord resumes paused listing'
);

-- ACTIVE -> RENTED
select lives_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'RENTED')$$,
    'landlord marks listing rented'
);

-- Disallowed transition: RENTED -> ACTIVE
select throws_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'ACTIVE')$$,
    'P0001', 'rejectChange: cannot transition from RENTED to ACTIVE',
    'cannot resume a rented listing directly'
);

-- RENTED -> DELETED (soft delete)
select lives_ok(
    $$select public.change_listing_status((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'DELETED')$$,
    'landlord deletes rented listing'
);

-- DELETED cannot be edited
select throws_ok(
    $$select public.update_listing(
        (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'),
        'New Title', '123 MG Road', 12.9716, 77.5946, 30000, 'APARTMENT'::public.property_type
    )$$,
    'P0001', 'rejectChange: listing is DELETED',
    'deleted listing cannot be edited'
);

-- DELETED listing takes no new photos
select throws_ok(
    $$insert into public.listing_photos (listing_id, url, position)
      values ((select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001'), 'https://ref.supabase.co/storage/v1/object/public/listing-photos/11111111-0000-0000-0000-000000000001/' || (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')::text || '/1.jpg', 1)$$,
    '42501', null,
    'deleted listing cannot get new photos'
);

-- Landlord 2 cannot add photos to landlord 1's listing (run as postgres to look up the id)
reset role;
select set_config('test.l1', (select listing_id from public.listings where landlord_id = '11111111-0000-0000-0000-000000000001')::text, true);
set local role authenticated;
set local request.jwt.claims = '{"sub": "11111111-0000-0000-0000-000000000002", "role": "authenticated"}';

select throws_ok(
    $$insert into public.listing_photos (listing_id, url, position)
      values (current_setting('test.l1')::uuid,
              'https://ref.supabase.co/storage/v1/object/public/listing-photos/11111111-0000-0000-0000-000000000002/'
              || current_setting('test.l1') || '/5.jpg', 5)$$,
    '42501', null,
    'landlord 2 cannot add photos to landlord 1 listing'
);

reset role;

select * from finish();
rollback;
