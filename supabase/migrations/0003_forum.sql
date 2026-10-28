-- Process 5.0 Run Community Forum (Person A). LOGIC §4.7.
-- D4 is written and read only through these functions: the forum tables have
-- RLS on, no policies and no direct grants.

revoke all on public.forum_threads, public.forum_posts, public.post_reactions, public.post_reports
    from anon, authenticated;

-- A thread is open when it is the general thread or its listing is ACTIVE.
create function public.thread_is_open(p_thread_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.forum_threads t
        left join public.listings l on l.listing_id = t.listing_id
        where t.thread_id = p_thread_id
          and (t.listing_id is null or l.status = 'ACTIVE')
    )
$$;

-- Called by create_listing (Person B) in the same transaction, LOGIC §4.1 step 5.
-- Idempotent: returns the existing thread if the listing already has one.
create function public.create_thread(p_listing_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_thread_id uuid;
begin
    if not exists (
        select 1 from public.listings
        where listing_id = p_listing_id and landlord_id = auth.uid()
    ) then
        raise exception 'accessDenied';
    end if;

    insert into public.forum_threads (listing_id) values (p_listing_id)
    on conflict (listing_id) do nothing
    returning thread_id into v_thread_id;

    if v_thread_id is null then
        select thread_id into v_thread_id from public.forum_threads where listing_id = p_listing_id;
    end if;
    return v_thread_id;
end;
$$;

-- General thread first, then one thread per ACTIVE listing, newest first.
create function public.get_threads()
returns table (thread_id uuid, listing_id uuid, listing_title text, created_at timestamptz)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
begin
    if public.current_user_role() is null or public.current_user_role() not in ('TENANT', 'ADMIN') then
        raise exception 'accessDenied';
    end if;

    return query
        select t.thread_id, t.listing_id, l.title, t.created_at
        from public.forum_threads t
        left join public.listings l on l.listing_id = t.listing_id
        where t.listing_id is null or l.status = 'ACTIVE'
        order by (t.listing_id is null) desc, t.created_at desc;
end;
$$;

-- Posts of one thread, oldest first, without removed posts. Authors appear by
-- name only. reactions is a {reaction: count} object.
create function public.get_posts(p_thread_id uuid)
returns table (
    post_id uuid,
    thread_id uuid,
    author_id uuid,
    author_name text,
    parent_post_id uuid,
    body text,
    created_at timestamptz,
    reactions jsonb
)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
begin
    if public.current_user_role() is null or public.current_user_role() not in ('TENANT', 'ADMIN') then
        raise exception 'accessDenied';
    end if;
    if not public.thread_is_open(p_thread_id) then
        raise exception 'listingUnavailable';
    end if;

    return query
        select p.post_id, p.thread_id, p.author_id, u.name, p.parent_post_id, p.body, p.created_at,
               coalesce(
                   (select jsonb_object_agg(r.reaction, r.n)
                    from (select pr.reaction, count(*) as n
                          from public.post_reactions pr
                          where pr.post_id = p.post_id
                          group by pr.reaction) r),
                   '{}'::jsonb)
        from public.forum_posts p
        join public.users u on u.user_id = p.author_id
        where p.thread_id = p_thread_id and not p.removed
        order by p.created_at;
end;
$$;

-- Tenant only. p_parent_post_id makes it a reply to a post in the same thread.
create function public.post_in_thread(p_thread_id uuid, p_body text, p_parent_post_id uuid default null)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_post_id uuid;
begin
    if public.current_user_role() is distinct from 'TENANT' then
        raise exception 'accessDenied';
    end if;
    if not public.thread_is_open(p_thread_id) then
        raise exception 'listingUnavailable';
    end if;
    if p_parent_post_id is not null and not exists (
        select 1 from public.forum_posts
        where post_id = p_parent_post_id and thread_id = p_thread_id and not removed
    ) then
        raise exception 'reply target is not a post in this thread';
    end if;

    insert into public.forum_posts (thread_id, author_id, parent_post_id, body)
    values (p_thread_id, auth.uid(), p_parent_post_id, trim(p_body))
    returning post_id into v_post_id;
    return v_post_id;
end;
$$;

-- Shared check for react and report: the post exists, is not removed and its
-- thread is open.
create function public.require_open_post(p_post_id uuid)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_thread_id uuid;
begin
    select thread_id into v_thread_id from public.forum_posts where post_id = p_post_id and not removed;
    if v_thread_id is null then
        raise exception 'post not found';
    end if;
    if not public.thread_is_open(v_thread_id) then
        raise exception 'listingUnavailable';
    end if;
end;
$$;

-- Tenant only. Reacting twice with the same reaction changes nothing.
create function public.react_to_post(p_post_id uuid, p_reaction text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if public.current_user_role() is distinct from 'TENANT' then
        raise exception 'accessDenied';
    end if;
    perform public.require_open_post(p_post_id);

    insert into public.post_reactions (post_id, user_id, reaction)
    values (p_post_id, auth.uid(), p_reaction)
    on conflict do nothing;
end;
$$;

-- Tenant only. Flags the post for the Admin. One report per tenant per post.
create function public.report_post(p_post_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if public.current_user_role() is distinct from 'TENANT' then
        raise exception 'accessDenied';
    end if;
    perform public.require_open_post(p_post_id);

    insert into public.post_reports (post_id, reporter_id)
    values (p_post_id, auth.uid())
    on conflict do nothing;
end;
$$;

-- Admin only (ARCHITECTURE §8.6).
create function public.remove_post(p_post_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not public.is_admin() then
        raise exception 'accessDenied';
    end if;
    update public.forum_posts set removed = true where post_id = p_post_id;
    if not found then
        raise exception 'post not found';
    end if;
end;
$$;

-- Admin only: reported posts that are not removed yet, most reported first.
create function public.get_reported_posts()
returns table (
    post_id uuid,
    thread_id uuid,
    listing_title text,
    author_name text,
    body text,
    created_at timestamptz,
    report_count bigint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
begin
    if not public.is_admin() then
        raise exception 'accessDenied';
    end if;

    return query
        select p.post_id, p.thread_id, l.title, u.name, p.body, p.created_at, count(r.report_id)
        from public.forum_posts p
        join public.post_reports r on r.post_id = p.post_id
        join public.users u on u.user_id = p.author_id
        join public.forum_threads t on t.thread_id = p.thread_id
        left join public.listings l on l.listing_id = t.listing_id
        where not p.removed
        group by p.post_id, p.thread_id, l.title, u.name, p.body, p.created_at
        order by count(r.report_id) desc, p.created_at;
end;
$$;

-- Only signed-in users can call the forum API; internal helpers are not exposed.
revoke execute on function public.thread_is_open(uuid) from public, anon, authenticated;
revoke execute on function public.require_open_post(uuid) from public, anon, authenticated;
revoke execute on function
    public.create_thread(uuid),
    public.get_threads(),
    public.get_posts(uuid),
    public.post_in_thread(uuid, text, uuid),
    public.react_to_post(uuid, text),
    public.report_post(uuid),
    public.remove_post(uuid),
    public.get_reported_posts()
    from public, anon;
grant execute on function
    public.create_thread(uuid),
    public.get_threads(),
    public.get_posts(uuid),
    public.post_in_thread(uuid, text, uuid),
    public.react_to_post(uuid, text),
    public.report_post(uuid),
    public.remove_post(uuid),
    public.get_reported_posts()
    to authenticated;
