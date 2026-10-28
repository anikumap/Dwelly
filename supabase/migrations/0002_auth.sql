-- Process 1.0 Authenticate & Authorize (Person A).
-- Role helpers, the sign-up trigger that creates public.users, and RLS for D1.
-- Also turns RLS on for every table, so a table with no policy denies all
-- access. B and C add the policies for their own tables.

-- Role of the caller, or NULL when not signed in. SECURITY DEFINER so policies
-- on other tables can call it without needing read access to public.users.
-- (Not named current_role: that is a reserved SQL keyword.)
create function public.current_user_role()
returns public.role
language sql
stable
security definer
set search_path = ''
as $$
    select role from public.users where user_id = auth.uid()
$$;

create function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(public.current_user_role() = 'ADMIN', false)
$$;

revoke execute on function public.current_user_role() from public, anon;
revoke execute on function public.is_admin() from public, anon;
grant execute on function public.current_user_role() to authenticated;
grant execute on function public.is_admin() to authenticated;

-- Sign-up: copy name, phone and role from the auth metadata into public.users.
-- Only LANDLORD or TENANT can be chosen. Users added without metadata (e.g. from
-- the Supabase dashboard) become TENANT; promote an Admin in the SQL editor:
--   update public.users set role = 'ADMIN' where email = '...';
create function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_role text := coalesce(new.raw_user_meta_data ->> 'role', 'TENANT');
    v_name text := coalesce(nullif(trim(new.raw_user_meta_data ->> 'name'), ''), split_part(new.email, '@', 1));
begin
    if v_role not in ('LANDLORD', 'TENANT') then
        raise exception 'accessDenied: role must be LANDLORD or TENANT';
    end if;
    insert into public.users (user_id, name, email, phone, role)
    values (new.id, v_name, new.email, nullif(trim(new.raw_user_meta_data ->> 'phone'), ''), v_role::public.role);
    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- RLS on every table. No policy = no access.
alter table public.users enable row level security;
alter table public.listings enable row level security;
alter table public.listing_photos enable row level security;
alter table public.listing_stats enable row level security;
alter table public.listing_views enable row level security;
alter table public.listing_saves enable row level security;
alter table public.enquiries enable row level security;
alter table public.agreements enable row level security;
alter table public.forum_threads enable row level security;
alter table public.forum_posts enable row level security;
alter table public.post_reactions enable row level security;
alter table public.post_reports enable row level security;

-- D1 users: each user reads and updates only their own row. Rows are created by
-- the trigger only. role and email can never be changed through the API.
revoke all on public.users from anon, authenticated;
grant select on public.users to authenticated;
grant update (name, phone, location_label, location_lat, location_lng) on public.users to authenticated;

create policy users_select_own on public.users
    for select to authenticated
    using (user_id = (select auth.uid()));

create policy users_update_own on public.users
    for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- Names only, for showing forum authors and agreement parties without exposing
-- email or phone (ARCHITECTURE §8.3). Runs as the view owner, so it bypasses
-- the own-row policy above on purpose.
create view public.user_names as
    select user_id, name from public.users;

revoke all on public.user_names from anon, authenticated;
grant select on public.user_names to authenticated;
