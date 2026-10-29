-- Safe re-run script for 0004_listings.sql
-- Nothing is dropped or deleted.
-- Only creates functions + storage bucket (all idempotent by nature).
-- Run this if 0004_listings.sql was partially applied and policies already exist.

-- Helper function
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

-- RPC: create_listing
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

-- RPC: update_listing
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

-- RPC: change_listing_status
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

-- RPC: record_stat
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
    v_rows integer;
begin
    if auth.uid() is null then
        raise exception 'accessDenied';
    end if;

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

        update public.listing_stats set views = views + 1 where listing_id = p_listing_id;

    elsif p_metric = 'SAVE' then
        if coalesce(p_saved, true) then
            insert into public.listing_saves (user_id, listing_id)
            values (auth.uid(), p_listing_id)
            on conflict do nothing;

            get diagnostics v_rows = row_count;
            if v_rows > 0 then
                update public.listing_stats set saves = saves + 1 where listing_id = p_listing_id;
            end if;
        else
            delete from public.listing_saves where user_id = auth.uid() and listing_id = p_listing_id;

            get diagnostics v_rows = row_count;
            if v_rows > 0 then
                update public.listing_stats set saves = greatest(0, saves - 1) where listing_id = p_listing_id;
            end if;
        end if;

    elsif p_metric = 'ENQUIRY' then
        if p_message is null or length(trim(p_message)) = 0 then
            raise exception 'rejectChange: message is required for enquiry';
        end if;

        insert into public.enquiries (listing_id, tenant_id, message)
        values (p_listing_id, auth.uid(), trim(p_message));

        update public.listing_stats set enquiries = enquiries + 1 where listing_id = p_listing_id;
    end if;
end;
$$;

-- Grant execute permissions
revoke execute on function public.create_listing from public, anon;
revoke execute on function public.update_listing from public, anon;
revoke execute on function public.change_listing_status from public, anon;
revoke execute on function public.record_stat from public, anon;

grant execute on function public.create_listing to authenticated;
grant execute on function public.update_listing to authenticated;
grant execute on function public.change_listing_status to authenticated;
grant execute on function public.record_stat to authenticated;

-- Storage bucket (ON CONFLICT DO NOTHING = skip if already exists)
insert into storage.buckets (id, name, public)
values ('listing-photos', 'listing-photos', true)
on conflict (id) do nothing;
