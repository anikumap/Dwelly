-- Person A: access rules for D1 (users) and D4 (forum). ARCHITECTURE §8.
-- Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(29);

-- Fixtures (run as postgres) ------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
    ('aaaaaaaa-0000-0000-0000-000000000001', 'tenant@test.dev',   '{"name": "Tina", "role": "TENANT", "phone": "9000000001"}'),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'tenant2@test.dev',  '{"name": "Tom", "role": "TENANT"}'),
    ('bbbbbbbb-0000-0000-0000-000000000001', 'landlord@test.dev', '{"name": "Lal", "role": "LANDLORD", "phone": "9000000002"}'),
    ('cccccccc-0000-0000-0000-000000000001', 'admin@test.dev',    '{}');
update public.users set role = 'ADMIN' where email = 'admin@test.dev';

insert into public.listings (listing_id, landlord_id, title, address, lat, lng, rent, property_type, status) values
    ('dddddddd-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', 'Active flat', 'Addr 1', 12.97, 77.59, 20000, 'APARTMENT', 'ACTIVE'),
    ('dddddddd-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000001', 'Paused flat', 'Addr 2', 12.97, 77.59, 25000, 'APARTMENT', 'PAUSED');
insert into public.forum_threads (thread_id, listing_id) values
    ('eeeeeeee-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000001'),
    ('eeeeeeee-0000-0000-0000-000000000002', 'dddddddd-0000-0000-0000-000000000002');

-- Sign-up trigger -----------------------------------------------------------
select is((select role::text from public.users where email = 'tenant@test.dev'), 'TENANT',
    'sign-up creates the users row with the chosen role');
select is((select name from public.users where email = 'admin@test.dev'), 'admin',
    'sign-up without metadata falls back to TENANT and the email name');
select throws_ok(
    $$insert into auth.users (id, email, raw_user_meta_data)
      values ('aaaaaaaa-0000-0000-0000-000000000009', 'x@test.dev', '{"role": "ADMIN"}')$$,
    'P0001', 'accessDenied: role must be LANDLORD or TENANT',
    'ADMIN cannot be chosen at sign-up');
select hasnt_column('public', 'user_names', 'email', 'user_names exposes no email');
select hasnt_column('public', 'user_names', 'phone', 'user_names exposes no phone');

-- Anonymous -----------------------------------------------------------------
set local role anon;
select throws_ok('select * from public.get_threads()', '42501', null, 'anon cannot call the forum API');
select throws_ok('select * from public.users', '42501', null, 'anon cannot read users');
reset role;

-- Tenant --------------------------------------------------------------------
set local role authenticated;
set local request.jwt.claims = '{"sub": "aaaaaaaa-0000-0000-0000-000000000001", "role": "authenticated"}';

select is((select count(*)::int from public.users), 1, 'a user reads only their own row');
select throws_ok($$update public.users set role = 'ADMIN'$$, '42501', null, 'role cannot be changed');
select lives_ok($$update public.users set location_label = 'Indiranagar'$$, 'own profile fields can be changed');
select is((select count(*)::int from public.user_names), 4, 'names of all users are readable');
select throws_ok('select * from public.forum_posts', '42501', null, 'forum tables are not directly readable');
select throws_ok($$insert into public.forum_threads (listing_id) values (null)$$, '42501', null,
    'forum tables are not directly writable');

select is((select count(*)::int from public.get_threads()), 2, 'general thread + ACTIVE listing thread');
select is((select listing_id from public.get_threads() limit 1), null, 'general thread comes first');
select is((select count(*)::int from public.get_threads() where listing_id = 'dddddddd-0000-0000-0000-000000000002'), 0,
    'thread of a PAUSED listing is hidden');

select isnt(public.post_in_thread((select thread_id from public.get_threads() where listing_id is null), 'Hello'), null,
    'tenant posts in the general thread');
select lives_ok(
    $$select public.post_in_thread(
        (select thread_id from public.get_threads() where listing_id is null), 'Reply',
        (select post_id from public.get_posts((select thread_id from public.get_threads() where listing_id is null)) limit 1))$$,
    'tenant replies to a post');
select throws_ok(
    $$select public.post_in_thread('eeeeeeee-0000-0000-0000-000000000002', 'Hi')$$,
    'P0001', 'listingUnavailable', 'cannot post in the thread of a PAUSED listing');
select lives_ok(
    $$select public.react_to_post((select post_id from public.get_posts((select thread_id from public.get_threads() where listing_id is null)) limit 1), 'LIKE')$$,
    'tenant reacts');
select is(
    (select reactions ->> 'LIKE' from public.get_posts((select thread_id from public.get_threads() where listing_id is null)) limit 1),
    '1', 'reaction is counted');
select lives_ok(
    $$select public.report_post((select post_id from public.get_posts((select thread_id from public.get_threads() where listing_id is null)) limit 1))$$,
    'tenant reports a post');
select throws_ok(
    $$select public.remove_post((select post_id from public.get_posts((select thread_id from public.get_threads() where listing_id is null)) limit 1))$$,
    'P0001', 'accessDenied', 'a tenant cannot remove a post');
select throws_ok('select * from public.get_reported_posts()', 'P0001', 'accessDenied', 'a tenant cannot see reports');

-- Another tenant cannot create threads for someone else's listing.
set local request.jwt.claims = '{"sub": "aaaaaaaa-0000-0000-0000-000000000002", "role": "authenticated"}';
select throws_ok($$select public.create_thread('dddddddd-0000-0000-0000-000000000001')$$,
    'P0001', 'accessDenied', 'only the listing owner can create its thread');

-- Landlord ------------------------------------------------------------------
set local request.jwt.claims = '{"sub": "bbbbbbbb-0000-0000-0000-000000000001", "role": "authenticated"}';
select throws_ok('select * from public.get_threads()', 'P0001', 'accessDenied', 'a landlord has no forum access');
select is(public.create_thread('dddddddd-0000-0000-0000-000000000001'), 'eeeeeeee-0000-0000-0000-000000000001'::uuid,
    'create_thread is idempotent for the owner');

-- Admin ---------------------------------------------------------------------
set local request.jwt.claims = '{"sub": "cccccccc-0000-0000-0000-000000000001", "role": "authenticated"}';
select is((select report_count::int from public.get_reported_posts()), 1, 'admin sees the reported post');
select lives_ok($$select public.remove_post((select post_id from public.get_reported_posts() limit 1))$$,
    'admin removes a reported post');

reset role;

select * from finish();
rollback;
