-- =====================================================================
-- Tree Care App (Heilbronn) - Supabase schema
-- Run once in: Supabase Dashboard > SQL Editor > New query > Run
-- =====================================================================

-- ---------- 1. PROFILES (extends Supabase auth.users) ----------------
create table public.profiles (
  id          uuid primary key references auth.users(id) on delete cascade,
  name        text not null default '',
  user_type   text check (user_type in ('resident', 'student')),
  phone       text,                       -- optional (GDPR: data minimisation)
  created_at  timestamptz not null default now()
);

-- Auto-create a profile row when someone signs up
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, name, user_type)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'name', ''),
    new.raw_user_meta_data->>'user_type'
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- 2. TREE SPECIES (lookup table) ---------------------------
create table public.tree_species (
  id    serial primary key,
  name  text unique not null
);

insert into public.tree_species (name) values
  ('Oak (Eiche)'), ('Linden (Linde)'), ('Maple (Ahorn)'),
  ('Beech (Buche)'), ('Birch (Birke)'), ('Hornbeam (Hainbuche)'),
  ('Plane tree (Platane)'), ('Other');

-- ---------- 3. TREES -------------------------------------------------
create table public.trees (
  id          uuid primary key default gen_random_uuid(),
  qr_code_id  text unique not null,
  species_id  int references public.tree_species(id),
  lat         numeric(9,6) not null check (lat between -90 and 90),
  lng         numeric(9,6) not null check (lng between -180 and 180),
  plant_year  int check (plant_year between 1900 and 2100),
  created_at  timestamptz not null default now()
);

create index trees_location_idx on public.trees (lat, lng);

-- ---------- 4. MISSIONS (plant / care / report) ----------------------
-- mission_code: 8 digits. 1xxxxxxx = plant, 3xxxxxxx = care, 5xxxxxxx = report
create table public.missions (
  id            bigint generated always as identity primary key,
  mission_code  text unique not null check (mission_code ~ '^[135][0-9]{7}$'),
  type          text not null check (type in ('plant', 'care', 'report')),
  user_id       uuid not null references public.profiles(id) on delete cascade,
  tree_id       uuid references public.trees(id) on delete set null,
  created_at    timestamptz not null default now(),
  lat           numeric(9,6) not null check (lat between -90 and 90),
  lng           numeric(9,6) not null check (lng between -180 and 180),
  photo_path    text,   -- path in Supabase Storage bucket 'mission-photos'

  -- prefix must match type
  constraint mission_code_matches_type check (
    (type = 'plant'  and mission_code like '1%') or
    (type = 'care'   and mission_code like '3%') or
    (type = 'report' and mission_code like '5%')
  ),
  -- plant and care missions must reference a tree; reports may not
  constraint tree_required_for_plant_care check (
    type = 'report' or tree_id is not null
  )
);

create index missions_user_idx on public.missions (user_id, created_at desc);
create index missions_tree_idx on public.missions (tree_id);

-- Anti-cheat: max one care mission per user, per tree, per day (Berlin time)
create unique index one_care_per_tree_per_day
  on public.missions (user_id, tree_id, ((created_at at time zone 'Europe/Berlin')::date))
  where type = 'care';

-- Auto-generate the 8-digit mission code if not supplied
create or replace function public.generate_mission_code()
returns trigger
language plpgsql
as $$
declare
  prefix text;
  candidate text;
begin
  if new.mission_code is not null then
    return new;
  end if;

  prefix := case new.type
              when 'plant'  then '1'
              when 'care'   then '3'
              when 'report' then '5'
            end;

  loop
    candidate := prefix || lpad((floor(random() * 10000000))::int::text, 7, '0');
    exit when not exists (select 1 from public.missions where mission_code = candidate);
  end loop;

  new.mission_code := candidate;
  return new;
end;
$$;

-- Note: mission_code is NOT NULL, so the trigger needs a placeholder default.
-- Drop the NOT NULL check at insert time by giving the column a temporary default:
alter table public.missions alter column mission_code drop not null;

create trigger set_mission_code
  before insert on public.missions
  for each row execute function public.generate_mission_code();

-- ---------- 5. CARE DETAILS -----------------------------------------
create table public.care_details (
  mission_id      bigint primary key references public.missions(id) on delete cascade,
  moisture_level  smallint check (moisture_level between 0 and 100),  -- % (user estimate)
  sensor_id       int check (sensor_id between 0000 and 9999)
);

-- ---------- 6. REPORT DETAILS ---------------------------------------
create table public.report_details (
  mission_id   bigint primary key references public.missions(id) on delete cascade,
  category     text not null check (category in
                 ('dead_tree', 'damaged', 'vandalism', 'broken_support', 'missing_tree', 'other')),
  description  text,
  status       text not null default 'open' check (status in ('open', 'in_progress', 'resolved'))
);

-- =====================================================================
-- ROW LEVEL SECURITY
-- =====================================================================
alter table public.profiles       enable row level security;
alter table public.tree_species   enable row level security;
alter table public.trees          enable row level security;
alter table public.missions       enable row level security;
alter table public.care_details   enable row level security;
alter table public.report_details enable row level security;

-- profiles: users see and edit only their own
create policy "profiles_select_own" on public.profiles
  for select to authenticated using (id = auth.uid());
create policy "profiles_update_own" on public.profiles
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- species: readable by everyone logged in
create policy "species_read" on public.tree_species
  for select to authenticated using (true);

-- trees: readable by all logged-in users, can be created when planting
create policy "trees_read" on public.trees
  for select to authenticated using (true);
create policy "trees_insert" on public.trees
  for insert to authenticated with check (true);

-- missions: users see and create only their own
create policy "missions_select_own" on public.missions
  for select to authenticated using (user_id = auth.uid());
create policy "missions_insert_own" on public.missions
  for insert to authenticated with check (user_id = auth.uid());

-- care_details
create policy "care_details_select_own" on public.care_details
  for select to authenticated using (
    exists (select 1 from public.missions m
            where m.id = mission_id and m.user_id = auth.uid()));
create policy "care_details_insert_own" on public.care_details
  for insert to authenticated with check (
    exists (select 1 from public.missions m
            where m.id = mission_id and m.user_id = auth.uid() and m.type = 'care'));

-- report_details
create policy "report_details_select_own" on public.report_details
  for select to authenticated using (
    exists (select 1 from public.missions m
            where m.id = mission_id and m.user_id = auth.uid()));
create policy "report_details_insert_own" on public.report_details
  for insert to authenticated with check (
    exists (select 1 from public.missions m
            where m.id = mission_id and m.user_id = auth.uid() and m.type = 'report'));

-- =====================================================================
-- STORAGE (photos)
-- Files should be uploaded to: mission-photos/<user_id>/<filename>
-- =====================================================================
insert into storage.buckets (id, name, public)
values ('mission-photos', 'mission-photos', false)
on conflict (id) do nothing;

create policy "photos_upload_own" on storage.objects
  for insert to authenticated with check (
    bucket_id = 'mission-photos'
    and (storage.foldername(name))[1] = auth.uid()::text);

create policy "photos_read_own" on storage.objects
  for select to authenticated using (
    bucket_id = 'mission-photos'
    and (storage.foldername(name))[1] = auth.uid()::text);
