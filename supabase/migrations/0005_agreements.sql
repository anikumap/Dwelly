-- Process 3.0 & 4.0 Tenant Discovery & Agreements (Person C). LOGIC §3, §4.2, §4.3, §4.5, §4.6.

-- RLS policies for agreements
revoke all on public.agreements from anon;
grant select on public.agreements to authenticated;

create policy agreements_select on public.agreements
    for select to authenticated
    using (
        (public.current_user_role() = 'LANDLORD' and landlord_id = (select auth.uid()))
        or (public.current_user_role() = 'TENANT' and tenant_id = (select auth.uid()) and status in ('SENT', 'SIGNED'))
        or public.is_admin()
    );

-- Helper RPC: get_tenant_id_by_email (for landlord creating agreement)
create function public.get_tenant_id_by_email(p_email text)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
    select user_id from public.users
    where lower(email) = lower(trim(p_email))
      and role = 'TENANT'
    limit 1;
$$;

-- RPC: popular_listings (LOGIC §4.6 / §5)
create function public.popular_listings()
returns setof public.listings
language sql
stable
security definer
set search_path = ''
as $$
    select l.*
    from public.listings l
    join public.listing_stats s on s.listing_id = l.listing_id
    where l.status = 'ACTIVE'
    order by (s.views + s.saves + s.enquiries) desc, l.created_at desc;
$$;

-- RPC: create_agreement (LOGIC §4.5)
create function public.create_agreement(p_listing_id uuid, p_tenant_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_listing public.listings;
    v_agreement public.agreements;
    v_terms jsonb;
begin
    if public.current_user_role() is distinct from 'LANDLORD' then
        raise exception 'accessDenied';
    end if;

    select * into v_listing from public.listings where listing_id = p_listing_id and landlord_id = auth.uid();
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_listing.status != 'RENTED' then
        raise exception 'rejectAgreement: listing must be RENTED to create an agreement';
    end if;

    if exists (select 1 from public.agreements where listing_id = p_listing_id) then
        raise exception 'rejectAgreement: agreement already exists for this listing';
    end if;

    if not exists (select 1 from public.users where user_id = p_tenant_id and role = 'TENANT') then
        raise exception 'rejectAgreement: invalid tenant';
    end if;

    -- Prefill default terms from listing
    v_terms := jsonb_build_object(
        'parties', 'Landlord: ' || (select name from public.users where user_id = auth.uid()) || ', Tenant: ' || (select name from public.users where user_id = p_tenant_id),
        'property', v_listing.title || ' at ' || v_listing.address,
        'lease_term', coalesce(v_listing.lease_months::text, '12') || ' months',
        'rent_terms', 'INR ' || v_listing.rent::text || ' per month',
        'deposit', 'INR ' || coalesce(v_listing.deposit::text, '0'),
        'maintenance_and_utilities', 'INR ' || coalesce(v_listing.maintenance::text, '0') || ' per month',
        'repair_responsibilities', 'Landlord handles structural repairs; tenant handles minor maintenance.',
        'house_rules', jsonb_to_text(v_listing.rules),
        'notice_and_termination', '1 month written notice required by either party.',
        'entry_and_inspection', '24-hour advance notice before landlord entry.',
        'inventory', 'Standard fittings as inspected.'
    );

    insert into public.agreements (listing_id, landlord_id, tenant_id, status, terms)
    values (p_listing_id, auth.uid(), p_tenant_id, 'DRAFT', v_terms)
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- RPC: edit_agreement (LOGIC §4.5)
create function public.edit_agreement(p_agreement_id uuid, p_terms jsonb)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
begin
    select * into v_agreement from public.agreements where agreement_id = p_agreement_id and landlord_id = auth.uid();
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status != 'DRAFT' then
        raise exception 'rejectAgreement: only DRAFT agreements can be edited';
    end if;

    update public.agreements
    set terms = p_terms
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- RPC: send_agreement (LOGIC §4.5)
create function public.send_agreement(p_agreement_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
    v_keys text[] := array['parties', 'property', 'lease_term', 'rent_terms', 'deposit', 'maintenance_and_utilities', 'repair_responsibilities', 'house_rules', 'notice_and_termination', 'entry_and_inspection', 'inventory'];
    k text;
begin
    select * into v_agreement from public.agreements where agreement_id = p_agreement_id and landlord_id = auth.uid();
    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status != 'DRAFT' then
        raise exception 'rejectAgreement: agreement is not in DRAFT status';
    end if;

    -- Validate that all required clauses are present and non-empty
    foreach k in array v_keys loop
        if v_agreement.terms ->> k is null or length(trim(v_agreement.terms ->> k)) = 0 then
            raise exception 'rejectAgreement: missing required clause %', k;
        end if;
    end loop;

    update public.agreements
    set status = 'SENT'
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

-- RPC: sign_agreement (LOGIC §4.5)
create function public.sign_agreement(p_agreement_id uuid)
returns public.agreements
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_agreement public.agreements;
    v_landlord_signed timestamptz;
    v_tenant_signed timestamptz;
    v_new_status public.agreement_status;
begin
    select * into v_agreement from public.agreements
    where agreement_id = p_agreement_id
      and (landlord_id = auth.uid() or tenant_id = auth.uid());

    if not found then
        raise exception 'accessDenied';
    end if;

    if v_agreement.status != 'SENT' then
        raise exception 'rejectAgreement: agreement must be SENT to be signed';
    end if;

    v_landlord_signed := v_agreement.landlord_signed_at;
    v_tenant_signed := v_agreement.tenant_signed_at;

    if auth.uid() = v_agreement.landlord_id then
        if v_landlord_signed is not null then
            raise exception 'rejectAgreement: landlord has already signed';
        end if;
        v_landlord_signed := now();
    elsif auth.uid() = v_agreement.tenant_id then
        if v_tenant_signed is not null then
            raise exception 'rejectAgreement: tenant has already signed';
        end if;
        v_tenant_signed := now();
    end if;

    if v_landlord_signed is not null and v_tenant_signed is not null then
        v_new_status := 'SIGNED';
    else
        v_new_status := 'SENT';
    end if;

    update public.agreements
    set landlord_signed_at = v_landlord_signed,
        tenant_signed_at = v_tenant_signed,
        status = v_new_status
    where agreement_id = p_agreement_id
    returning * into v_agreement;

    return v_agreement;
end;
$$;

grant execute on function public.get_tenant_id_by_email to authenticated;
grant execute on function public.popular_listings to authenticated;
grant execute on function public.create_agreement to authenticated;
grant execute on function public.edit_agreement to authenticated;
grant execute on function public.send_agreement to authenticated;
grant execute on function public.sign_agreement to authenticated;
