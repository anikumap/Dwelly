-- Process 2.0 Manage Listings (Person B). LOGIC §4.1, §4.4, §5.

-- Security definer helper to check if tenant is named on a SENT/SIGNED agreement
-- for this listing, avoiding RLS recursion and direct table access.
create function public.is_tenant_named_on_agreement(p_listing_id uuid, p_user_id uuid)
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

grant execute on function public.is_tenant_named_on_agreement(uuid, uuid) to authenticated;

-- RLS policies for listings
revoke all on public.listings from anon;
grant select, insert, update on public.listings to authenticated;

create policy listings_select on public.listings
    for select to authenticated
    using (
        (public.current_user_role() = 'LANDLORD' and landlord_id = (select auth.uid()))
        or (public.current_user_role() = 'TENANT' and (status = 'ACTIVE' or public.is_tenant_named_on_agreement(listing_id, (select auth.uid()))))
        or public.is_admin()
    );

create policy listings_insert on public.listings
    for insert to authenticated
    with check (
        public.current_user_role() = 'LANDLORD' and landlord_id = (select auth.uid())
    );

create policy listings_update on public.listings
    for update to authenticated
    using (
        public.current_user_role() = 'LANDLORD' and landlord_id = (select auth.uid())
    )
    with check (
        public.current_user_role() = 'LANDLORD' and landlord_id = (select auth.uid())
    );

-- RLS policies for listing_photos
revoke all on public.listing_photos from anon;
grant select, insert, update, delete on public.listing_photos to authenticated;

create policy listing_photos_select on public.listing_photos
    for select to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
        )
    );

create policy listing_photos_write on public.listing_photos
    for all to authenticated
    using (
        exists (
            select 1 from public.listings l
            where l.listing_id = listing_photos.listing_id
              and l.landlord_id = (select auth.uid())
        )
    );

-- RLS policies for listing_stats and enquiries: readable only by owner
revoke all on public.listing_stats, public.listing_views, public.listing_saves, public.enquiries from anon;
grant select on public.listing_stats, public.enquiries to authenticated;

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

-- RPC: create_listing (LOGIC §4.1)
create function public.create_listing(
    p_title text,
    p_description text,
    p_address text,
    p_lat double precision,
    p_lng double precision,
    p_rent numeric,
    p_deposit numeric,
    p_maintenance numeric,
    p_available_from date,
    p_lease_months integer,
    p_property_type public.property_type,
    p_bedrooms integer,
    p_bathrooms integer,
    p_balconies integer,
    p_carpet_area numeric,
    p_floor_no integer,
    p_furnishing public.furnishing,
    p_parking_two_wheeler integer,
    p_parking_four_wheeler integer,
    p_amenities jsonb,
    p_rules jsonb,
    p_preferred_tenant public.preferred_tenant
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
        landlord_id, title, description, address, lat, lng, rent, deposit, maintenance,
        available_from, lease_months, property_type, bedrooms, bathrooms, balconies,
        carpet_area, floor_no, furnishing, parking_two_wheeler, parking_four_wheeler,
        amenities, rules, preferred_tenant, status, went_live_at
    )
    values (
        auth.uid(), trim(p_title), trim(p_description), trim(p_address), p_lat, p_lng, p_rent, p_deposit, p_maintenance,
        p_available_from, p_lease_months, p_property_type, p_bedrooms, p_bathrooms, p_balconies,
        p_carpet_area, p_floor_no, p_furnishing, coalesce(p_parking_two_wheeler, 0), coalesce(p_parking_four_wheeler, 0),
        coalesce(p_amenities, '[]'::jsonb), coalesce(p_rules, '[]'::jsonb), p_preferred_tenant, 'ACTIVE', now()
    )
    returning * into v_listing;

    insert into public.listing_stats (listing_id, views, saves, enquiries)
    values (v_listing.listing_id, 0, 0, 0);

    perform public.create_thread(v_listing.listing_id);

    return v_listing;
end;
$$;

-- RPC: update_listing (LOGIC §2)
create function public.update_listing(
    p_listing_id uuid,
    p_title text,
    p_description text,
-- RPC: update_listing -------------------------------------------------------
-- LOGIC §2: Allowed in any state except DELETED. Owner only.
create or replace function public.update_listing(
    p_listing_id uuid,
    p_title text,
    p_address text,
    p_lat double precision,
    p_lng double precision,
    p_rent numeric,
    p_deposit numeric,
    p_maintenance numeric,
    p_available_from date,
    p_lease_months integer,
    p_property_type public.property_type,
    p_bedrooms integer,
    p_bathrooms integer,
    p_balconies integer,
    p_carpet_area numeric,
    p_floor_no integer,
    p_furnishing public.furnishing,
    p_parking_two_wheeler integer,
    p_parking_four_wheeler integer,
    p_amenities jsonb,
    p_rules jsonb,
    p_preferred_tenant public.preferred_tenant
)
returns public.listings
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_listing public.listings;
    v_curr_status public.listing_status;
begin
    select status into v_curr_status from public.listings where listing_id = p_listing_id and landlord_id = auth.uid();
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_curr_status = 'DELETED' then
        raise exception 'rejectChange: cannot edit DELETED listing';
    end if;

    update public.listings
    set title = trim(p_title),
        description = trim(p_description),
        address = trim(p_address),
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

-- RPC: change_listing_status (LOGIC §2)
create function public.change_listing_status(p_listing_id uuid, p_status public.listing_status)
returns public.listings
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_curr_status public.listing_status;
    v_listing public.listings;
begin
    select status into v_curr_status from public.listings where listing_id = p_listing_id and landlord_id = auth.uid();
    if not found then
        raise exception 'accessDenied';
    end if;

    -- State machine validation
    if v_curr_status = p_status then
        select * into v_listing from public.listings where listing_id = p_listing_id;
        return v_listing;
    end if;

    if v_curr_status = 'DELETED' then
        raise exception 'rejectChange: DELETED listing status cannot be changed';
    elsif v_curr_status = 'RENTED' and p_status != 'DELETED' then
        raise exception 'rejectChange: RENTED listing can only be DELETED';
    elsif v_curr_status = 'PAUSED' and p_status not in ('ACTIVE', 'RENTED', 'DELETED') then
        raise exception 'rejectChange: invalid status transition';
    elsif v_curr_status = 'ACTIVE' and p_status not in ('PAUSED', 'RENTED', 'DELETED') then
        raise exception 'rejectChange: invalid status transition';
    end if;

    update public.listings
    set status = p_status
    where listing_id = p_listing_id
    returning * into v_listing;

    return v_listing;
end;
$$;

-- RPC: record_stat (LOGIC §5)
create function public.record_stat(
    p_listing_id uuid,
    p_metric public.stat_metric,
    p_message text default null,
    p_saved boolean default true
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_status public.listing_status;
    v_inserted boolean := false;
begin
    select status into v_status from public.listings where listing_id = p_listing_id;
    if not found then
        raise exception 'listingUnavailable';
    end if;

    if public.current_user_role() is distinct from 'TENANT' then
        raise exception 'accessDenied';
    end if;

    if p_metric = 'VIEW' then
        insert into public.listing_views (user_id, listing_id, last_viewed_at)
        values (auth.uid(), p_listing_id, now())
        on conflict (user_id, listing_id) do update set last_viewed_at = now();

        update public.listing_stats set views = views + 1 where listing_id = p_listing_id;

    elsif p_metric = 'SAVE' then
        if p_saved then
            insert into public.listing_saves (user_id, listing_id)
            values (auth.uid(), p_listing_id)
            on conflict do nothing;
            if found then
                update public.listing_stats set saves = saves + 1 where listing_id = p_listing_id;
            end if;
        else
            delete from public.listing_saves where user_id = auth.uid() and listing_id = p_listing_id;
            if found then
                update public.listing_stats set saves = greatest(0, saves - 1) where listing_id = p_listing_id;
            end if;
        end if;

    elsif p_metric = 'ENQUIRY' then
        if p_message is null or length(trim(p_message)) = 0 then
            raise exception 'rejectChange: enquiry message cannot be empty';
        end if;

        insert into public.enquiries (listing_id, tenant_id, message)
        values (p_listing_id, auth.uid(), trim(p_message));

        update public.listing_stats set enquiries = enquiries + 1 where listing_id = p_listing_id;
    end if;
end;
$$;

grant execute on function public.create_listing to authenticated;
grant execute on function public.update_listing to authenticated;
grant execute on function public.change_listing_status to authenticated;
grant execute on function public.record_stat to authenticated;
