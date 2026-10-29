-- Process 2.0 Manage Listings (Person B). ARCHITECTURE §6, §8, LOGIC §2, §4.1, §4.4, §5.
-- Tables: listings, listing_photos, listing_stats, listing_views, listing_saves, enquiries.

-- Revoke anon access from all Process 2.0 tables
revoke all on public.listings, public.listing_photos, public.listing_stats,
    public.listing_views, public.listing_saves, public.enquiries
    from anon;

grant select, insert, update on public.listings to authenticated;
grant select, insert, update, delete on public.listing_photos to authenticated;
grant select on public.listing_stats to authenticated;
grant select on public.enquiries to authenticated;
grant select on public.listing_views to authenticated;
grant select on public.listing_saves to authenticated;

-- Helper function: avoids RLS recursion on agreements and decouples from Person C policies.
create or replace function public.is_tenant_on_agreement(p_listing_id uuid, p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1 from public.agreements
        where listing_id = p_listing_id
          and tenant_id = p_user_id
          and status in ('SENT', 'SIGNED')
    )
$$;

revoke execute on function public.is_tenant_on_agreement(uuid, uuid) from public, anon;
grant execute on function public.is_tenant_on_agreement(uuid, uuid) to authenticated;

-- RLS: listings -------------------------------------------------------------
-- Drop existing policies first so this script is idempotent / re-runnable.
drop policy if exists listings_select on public.listings;
drop policy if exists listings_insert on public.listings;
drop policy if exists listings_update on public.listings;

-- Select: owner, or a TENANT when ACTIVE, or the tenant named on a SENT/SIGNED agreement
create policy listings_select on public.listings
    for select to authenticated
    using (
        landlord_id = (select auth.uid())
        or (public.current_user_role() = 'TENANT' and status = 'ACTIVE')
        or public.is_tenant_on_agreement(listing_id, (select auth.uid()))
    );

create policy listings_insert on public.listings
    for insert to authenticated
    with check (
        public.current_user_role() = 'LANDLORD'
        and landlord_id = (select auth.uid())
    );

create policy listings_update on public.listings
    for update to authenticated
    using (
        public.current_user_role() = 'LANDLORD'
        and landlord_id = (select auth.uid())
    )
    with check (
        public.current_user_role() = 'LANDLORD'
        and landlord_id = (select auth.uid())
    );

-- RLS: listing_photos -------------------------------------------------------
-- Readable wherever the listing is readable; writable only by the owner
drop policy if exists listing_photos_select on public.listing_photos;
drop policy if exists listing_photos_insert on public.listing_photos;
drop policy if exists listing_photos_update on public.listing_photos;
drop policy if exists listing_photos_delete on public.listing_photos;

create policy listing_photos_select on public.listing_photos
    for select to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
        )
    );

create policy listing_photos_insert on public.listing_photos
    for insert to authenticated
    with check (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

create policy listing_photos_update on public.listing_photos
    for update to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

create policy listing_photos_delete on public.listing_photos
    for delete to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

-- RLS: listing_stats and enquiries ------------------------------------------
-- Readable only by the listing owner. Writes occur only via RPC.
drop policy if exists listing_stats_select on public.listing_stats;
drop policy if exists enquiries_select on public.enquiries;

create policy listing_stats_select on public.listing_stats
    for select to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_stats.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

create policy enquiries_select on public.enquiries
    for select to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = enquiries.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

-- RLS: listing_views and listing_saves --------------------------------------
-- Readable only by the user who created them
drop policy if exists listing_views_select on public.listing_views;
drop policy if exists listing_saves_select on public.listing_saves;

create policy listing_views_select on public.listing_views
    for select to authenticated
    using (user_id = (select auth.uid()));

create policy listing_saves_select on public.listing_saves
    for select to authenticated
    using (user_id = (select auth.uid()));

-- RPC: create_listing -------------------------------------------------------
-- LOGIC §4.1: in one transaction:
-- 1. Check caller is LANDLORD
-- 2. Insert listing with status = 'ACTIVE', went_live_at = now(), landlord_id = auth.uid()
-- 3. Insert listing_stats at 0/0/0
-- 4. perform public.create_thread(listing_id)
create or replace function public.create_listing(
    p_title text,
    p_address text,
    p_lat double precision,
    p_lng double precision,
    p_rent numeric,
    p_property_type public.property_type,
    p_description text default null,
    p_deposit numeric default null,
    p_maintenance numeric default null,
    p_available_from date default null,
    p_lease_months integer default null,
    p_bedrooms integer default null,
    p_bathrooms integer default null,
    p_balconies integer default null,
    p_carpet_area numeric default null,
    p_floor_no integer default null,
    p_furnishing public.furnishing default null,
    p_parking_two_wheeler integer default 0,
    p_parking_four_wheeler integer default 0,
    p_amenities jsonb default '[]'::jsonb,
    p_rules jsonb default '[]'::jsonb,
    p_preferred_tenant public.preferred_tenant default null
)
returns public.listings
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_listing public.listings;
begin
    if public.current_user_role() is distinct from 'LANDLORD' then
        raise exception 'accessDenied';
    end if;

    insert into public.listings (
        landlord_id, title, description, address, lat, lng,
        rent, deposit, maintenance, available_from, lease_months,
        property_type, bedrooms, bathrooms, balconies, carpet_area,
        floor_no, furnishing, parking_two_wheeler, parking_four_wheeler,
        amenities, rules, preferred_tenant, status, went_live_at
    ) values (
        auth.uid(), p_title, p_description, p_address, p_lat, p_lng,
        p_rent, p_deposit, p_maintenance, p_available_from, p_lease_months,
        p_property_type, p_bedrooms, p_bathrooms, p_balconies, p_carpet_area,
        p_floor_no, p_furnishing, coalesce(p_parking_two_wheeler, 0), coalesce(p_parking_four_wheeler, 0),
        coalesce(p_amenities, '[]'::jsonb), coalesce(p_rules, '[]'::jsonb), p_preferred_tenant,
        'ACTIVE', now()
    )
    returning * into v_listing;

    insert into public.listing_stats (listing_id, views, saves, enquiries)
    values (v_listing.listing_id, 0, 0, 0);

    perform public.create_thread(v_listing.listing_id);

    return v_listing;
end;
$$;

-- RPC: update_listing -------------------------------------------------------
-- LOGIC §2: Allowed in any state except DELETED. Owner only.
create or replace function public.update_listing(
    p_listing_id uuid,
    p_title text,
    p_address text,
    p_lat double precision,
    p_lng double precision,
    p_rent numeric,
    p_property_type public.property_type,
    p_description text default null,
    p_deposit numeric default null,
    p_maintenance numeric default null,
    p_available_from date default null,
    p_lease_months integer default null,
    p_bedrooms integer default null,
    p_bathrooms integer default null,
    p_balconies integer default null,
    p_carpet_area numeric default null,
    p_floor_no integer default null,
    p_furnishing public.furnishing default null,
    p_parking_two_wheeler integer default 0,
    p_parking_four_wheeler integer default 0,
    p_amenities jsonb default '[]'::jsonb,
    p_rules jsonb default '[]'::jsonb,
    p_preferred_tenant public.preferred_tenant default null
)
returns public.listings
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_status public.listing_status;
    v_listing public.listings;
begin
    select status into v_status
    from public.listings
    where listing_id = p_listing_id and landlord_id = auth.uid();

    if not found then
        raise exception 'accessDenied';
    end if;

    if v_status = 'DELETED' then
        raise exception 'rejectChange: listing is DELETED';
    end if;

    update public.listings set
        title = p_title,
        description = p_description,
        address = p_address,
        lat = p_lat,
        lng = p_lng,
        rent = p_rent,
        deposit = p_deposit,
        maintenance = p_maintenance,
        available_from = p_available_from,
        lease_months = p_lease_months,
        property_type = p_property_type,
        bedrooms = p_bedrooms,
        bathrooms = p_bathrooms,
        balconies = p_balconies,
        carpet_area = p_carpet_area,
        floor_no = p_floor_no,
        furnishing = p_furnishing,
        parking_two_wheeler = coalesce(p_parking_two_wheeler, 0),
        parking_four_wheeler = coalesce(p_parking_four_wheeler, 0),
        amenities = coalesce(p_amenities, '[]'::jsonb),
        rules = coalesce(p_rules, '[]'::jsonb),
        preferred_tenant = p_preferred_tenant
    where listing_id = p_listing_id
    returning * into v_listing;

    return v_listing;
end;
$$;

-- RPC: change_listing_status ------------------------------------------------
-- LOGIC §2 state machine:
-- ACTIVE -> PAUSED, RENTED, DELETED
-- PAUSED -> ACTIVE, RENTED, DELETED
-- RENTED -> DELETED
create or replace function public.change_listing_status(
    p_listing_id uuid,
    p_status public.listing_status
)
returns public.listings
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_current_status public.listing_status;
    v_listing public.listings;
begin
    select status into v_current_status
    from public.listings
    where listing_id = p_listing_id and landlord_id = auth.uid();

    if not found then
        raise exception 'accessDenied';
    end if;

    if v_current_status = p_status then
        select * into v_listing from public.listings where listing_id = p_listing_id;
        return v_listing;
    end if;

    if v_current_status = 'ACTIVE' and p_status in ('PAUSED', 'RENTED', 'DELETED') then
        -- allowed
    elsif v_current_status = 'PAUSED' and p_status in ('ACTIVE', 'RENTED', 'DELETED') then
        -- allowed
    elsif v_current_status = 'RENTED' and p_status = 'DELETED' then
        -- allowed
    else
        raise exception 'rejectChange: cannot transition from % to %', v_current_status, p_status;
    end if;

    update public.listings
    set status = p_status
    where listing_id = p_listing_id
    returning * into v_listing;

    return v_listing;
end;
$$;

-- RPC: record_stat ----------------------------------------------------------
-- LOGIC §4.3, §5: security definer update called from Discovery.
create or replace function public.record_stat(
    p_listing_id uuid,
    p_metric public.stat_metric,
    p_message text default null,
    p_saved boolean default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_inserted boolean;
    v_rows integer;
begin
    if auth.uid() is null then
        raise exception 'accessDenied';
    end if;

    -- Listing visibility check: ACTIVE or tenant is resident on agreement
    if not exists (
        select 1 from public.listings l
        where l.listing_id = p_listing_id
          and (l.status = 'ACTIVE' or public.is_tenant_on_agreement(l.listing_id, auth.uid()))
    ) then
        raise exception 'listingUnavailable';
    end if;

    if p_metric = 'VIEW' then
        insert into public.listing_views (user_id, listing_id, last_viewed_at)
        values (auth.uid(), p_listing_id, now())
        on conflict (user_id, listing_id) do update set last_viewed_at = now();

        update public.listing_stats
        set views = views + 1
        where listing_id = p_listing_id;

    elsif p_metric = 'SAVE' then
        if coalesce(p_saved, true) then
            insert into public.listing_saves (user_id, listing_id)
            values (auth.uid(), p_listing_id)
            on conflict do nothing;

            get diagnostics v_rows = row_count;
            if v_rows > 0 then
                update public.listing_stats
                set saves = saves + 1
                where listing_id = p_listing_id;
            end if;
        else
            delete from public.listing_saves
            where user_id = auth.uid() and listing_id = p_listing_id;

            get diagnostics v_rows = row_count;
            if v_rows > 0 then
                update public.listing_stats
                set saves = greatest(0, saves - 1)
                where listing_id = p_listing_id;
            end if;
        end if;

    elsif p_metric = 'ENQUIRY' then
        if p_message is null or length(trim(p_message)) = 0 then
            raise exception 'rejectChange: message is required for enquiry';
        end if;

        insert into public.enquiries (listing_id, tenant_id, message)
        values (p_listing_id, auth.uid(), trim(p_message));

        update public.listing_stats
        set enquiries = enquiries + 1
        where listing_id = p_listing_id;
    end if;
end;
$$;

revoke execute on function public.create_listing from public, anon;
revoke execute on function public.update_listing from public, anon;
revoke execute on function public.change_listing_status from public, anon;
revoke execute on function public.record_stat from public, anon;

grant execute on function public.create_listing to authenticated;
grant execute on function public.update_listing to authenticated;
grant execute on function public.change_listing_status to authenticated;
grant execute on function public.record_stat to authenticated;

-- Storage: listing-photos bucket --------------------------------------------
insert into storage.buckets (id, name, public)
values ('listing-photos', 'listing-photos', true)
on conflict (id) do nothing;

drop policy if exists "listing_photos_storage_select" on storage.objects;
drop policy if exists "listing_photos_storage_insert" on storage.objects;
drop policy if exists "listing_photos_storage_update" on storage.objects;
drop policy if exists "listing_photos_storage_delete" on storage.objects;

create policy "listing_photos_storage_select"
on storage.objects for select
using (bucket_id = 'listing-photos');

create policy "listing_photos_storage_insert"
on storage.objects for insert
with check (
    bucket_id = 'listing-photos'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy "listing_photos_storage_update"
on storage.objects for update
using (
    bucket_id = 'listing-photos'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy "listing_photos_storage_delete"
on storage.objects for delete
using (
    bucket_id = 'listing-photos'
    and (storage.foldername(name))[1] = (select auth.uid())::text
);
