-- Process 3.0 Discover Listings and 4.0 Agreements (Person C). LOGIC §3, §4.2, §4.3, §4.5, §4.6.
-- Safe to re-run in the SQL editor: policies are dropped first and functions use create or replace.

-- Start from no privileges (Supabase's defaults grant authenticated everything), as 0002 and
-- 0004 do. Clients only read; every write goes through the security definer functions below.
revoke all on public.agreements from anon, authenticated;
grant select on public.agreements to authenticated;

-- ARCHITECTURE §8: an Agreement is visible only to its two parties, and to the tenant only
-- from SENT onward.
drop policy if exists agreements_select on public.agreements;
create policy agreements_select on public.agreements
    for select to authenticated
    using (
        landlord_id = (select auth.uid())
        or (tenant_id = (select auth.uid()) and status in ('SENT', 'SIGNED'))
    );

-- Landlord only: finds the tenant to name on an agreement by the email they registered with.
create or replace function public.get_tenant_id_by_email(p_email text)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if public.current_user_role() is distinct from 'LANDLORD' then
        raise exception 'accessDenied';
    end if;
    return (
        select user_id from public.users
        where lower(email) = lower(trim(p_email)) and role = 'TENANT'
    );
end;
$$;

-- LOGIC §5: Popular = ACTIVE listings ordered by views + saves + enquiries, highest first.
-- Tenants only: a Landlord has no Discovery and can't see other landlords' listings (LOGIC §4.2).
create or replace function public.popular_listings()
returns setof public.listings
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if public.current_user_role() is distinct from 'TENANT' then
        raise exception 'accessDenied';
    end if;
    return query
        select l.*
        from public.listings l
        join public.listing_stats s on s.listing_id = l.listing_id
        where l.status = 'ACTIVE'
        order by (s.views + s.saves + s.enquiries) desc, l.created_at desc;
end;
$$;

-- LOGIC §4.5 createAgreement: Landlord owns the listing AND it is RENTED AND no Agreement yet.
-- Creates a DRAFT prefilled from the listing.
create or replace function public.create_agreement(p_listing_id uuid, p_tenant_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_listing public.listings;
    v_agreement public.agreements;
begin
    if public.current_user_role() is distinct from 'LANDLORD' then
        raise exception 'accessDenied';
    end if;

    -- Lock the listing so two drafts for it can't race past the "no Agreement yet" check.
    select * into v_listing from public.listings
    where listing_id = p_listing_id and landlord_id = auth.uid()
    for update;
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_listing.status <> 'RENTED' then
        raise exception 'rejectAgreement: listing must be RENTED to create an agreement';
    end if;
    if exists (select 1 from public.agreements where listing_id = p_listing_id) then
        raise exception 'rejectAgreement: this listing already has an agreement';
    end if;
    if not exists (select 1 from public.users where user_id = p_tenant_id and role = 'TENANT') then
        raise exception 'rejectAgreement: invalid tenant';
    end if;

    insert into public.agreements (listing_id, landlord_id, tenant_id, status, terms)
    values (
        p_listing_id, auth.uid(), p_tenant_id, 'DRAFT',
        jsonb_build_object(
            'parties', 'Landlord: ' || coalesce((select name from public.users where user_id = auth.uid()), '')
                || ', Tenant: ' || coalesce((select name from public.users where user_id = p_tenant_id), ''),
            'property', v_listing.title || ' at ' || v_listing.address,
            'lease_term', coalesce(v_listing.lease_months::text || ' months', ''),
            'rent_terms', 'INR ' || v_listing.rent::text || ' per month',
            'deposit', coalesce('INR ' || v_listing.deposit::text, ''),
            'maintenance_and_utilities', coalesce('INR ' || v_listing.maintenance::text || ' per month', ''),
            'repair_responsibilities', 'Landlord handles structural repairs; tenant handles minor maintenance.',
            'house_rules', coalesce(
                (select string_agg(r, ', ') from jsonb_array_elements_text(v_listing.rules) as r),
                'None specified'),
            'notice_and_termination', '1 month written notice required by either party.',
            'entry_and_inspection', '24-hour advance notice before landlord entry.',
            'inventory', 'Standard fittings as inspected.'
        )
    )
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- LOGIC §4.5 editAgreement: Landlord, DRAFT only.
create or replace function public.edit_agreement(p_agreement_id uuid, p_terms jsonb)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
begin
    select * into v_agreement from public.agreements
    where agreement_id = p_agreement_id and landlord_id = auth.uid()
    for update;
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status <> 'DRAFT' then
        raise exception 'rejectAgreement: only a DRAFT agreement can be edited';
    end if;
    if p_terms is null or jsonb_typeof(p_terms) <> 'object' then
        raise exception 'rejectAgreement: clauses must be a set of named texts';
    end if;

    update public.agreements set terms = p_terms
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- LOGIC §4.5 sendAgreement: Landlord, DRAFT AND all required clauses filled -> SENT.
-- The "signatures" clause is the two signed_at columns, filled by sign_agreement.
create or replace function public.send_agreement(p_agreement_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
    v_clause text;
begin
    select * into v_agreement from public.agreements
    where agreement_id = p_agreement_id and landlord_id = auth.uid()
    for update;
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status <> 'DRAFT' then
        raise exception 'rejectAgreement: agreement is not a DRAFT';
    end if;

    foreach v_clause in array array[
        'parties', 'property', 'lease_term', 'rent_terms', 'deposit', 'maintenance_and_utilities',
        'repair_responsibilities', 'house_rules', 'notice_and_termination', 'entry_and_inspection',
        'inventory'
    ] loop
        if length(trim(coalesce(v_agreement.terms ->> v_clause, ''))) = 0 then
            raise exception 'rejectAgreement: missing required clause %', v_clause;
        end if;
    end loop;

    update public.agreements set status = 'SENT'
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- LOGIC §4.5 sign: Landlord or the named Tenant, SENT, not yet signed by the caller.
-- Both signed -> SIGNED (LOGIC §3 invariant).
create or replace function public.sign_agreement(p_agreement_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
begin
    -- Lock the row: without it, landlord and tenant signing at once could each overwrite the
    -- other's signature with the null they read.
    select * into v_agreement from public.agreements
    where agreement_id = p_agreement_id
      and (landlord_id = auth.uid() or tenant_id = auth.uid())
    for update;
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status <> 'SENT' then
        raise exception 'rejectAgreement: agreement must be SENT to be signed';
    end if;

    if auth.uid() = v_agreement.landlord_id then
        if v_agreement.landlord_signed_at is not null then
            raise exception 'rejectAgreement: you have already signed';
        end if;
        v_agreement.landlord_signed_at := now();
    else
        if v_agreement.tenant_signed_at is not null then
            raise exception 'rejectAgreement: you have already signed';
        end if;
        v_agreement.tenant_signed_at := now();
    end if;

    update public.agreements
    set landlord_signed_at = v_agreement.landlord_signed_at,
        tenant_signed_at = v_agreement.tenant_signed_at,
        status = case
            when v_agreement.landlord_signed_at is not null and v_agreement.tenant_signed_at is not null
            then 'SIGNED'::public.agreement_status
            else 'SENT'::public.agreement_status
        end
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- Functions are executable by PUBLIC by default; only signed-in users may call these.
revoke execute on function public.get_tenant_id_by_email(text) from public, anon;
revoke execute on function public.popular_listings() from public, anon;
revoke execute on function public.create_agreement(uuid, uuid) from public, anon;
revoke execute on function public.edit_agreement(uuid, jsonb) from public, anon;
revoke execute on function public.send_agreement(uuid) from public, anon;
revoke execute on function public.sign_agreement(uuid) from public, anon;

grant execute on function public.get_tenant_id_by_email(text) to authenticated;
grant execute on function public.popular_listings() to authenticated;
grant execute on function public.create_agreement(uuid, uuid) to authenticated;
grant execute on function public.edit_agreement(uuid, jsonb) to authenticated;
grant execute on function public.send_agreement(uuid) to authenticated;
grant execute on function public.sign_agreement(uuid) to authenticated;
