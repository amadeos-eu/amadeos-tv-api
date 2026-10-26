-- The films of the broadcasters' media libraries, as the crawler finds them. A film is known by its
-- source and the id it has there; the videos stay on the broadcasters' servers, only the links are kept.
create table film (
    id                uuid primary key default gen_random_uuid(),
    source            text        not null,
    source_id         text        not null,
    channel           text        not null,
    topic             text        not null default '',
    title             text        not null,
    description       text        not null default '',
    published_at      timestamptz,
    duration_s        integer,
    available_until   timestamptz,
    geo               text        not null default '',
    website_url       text        not null default '',
    subtitle_url      text        not null default '',
    video_sd          text        not null default '',
    video_hd          text        not null default '',
    video_hls         text        not null default '',
    audio_description boolean     not null default false,
    sign_language     boolean     not null default false,
    first_seen        timestamptz not null,
    last_seen         timestamptz not null,
    gone_at           timestamptz,
    search            tsvector generated always as (
        setweight(to_tsvector('german', topic), 'A') ||
        setweight(to_tsvector('german', title), 'A') ||
        setweight(to_tsvector('german', description), 'B')) stored,
    unique (source, source_id)
);

create index film_search on film using gin (search);
create index film_published on film (published_at desc);
create index film_channel on film (channel, published_at desc);

-- One row per source and run, for /status and for finding out when a source broke.
create table crawl_run (
    id            bigint generated always as identity primary key,
    source        text        not null,
    started       timestamptz not null,
    finished      timestamptz,
    films_new     integer     not null default 0,
    films_updated integer     not null default 0,
    error         text
);

create index crawl_run_source on crawl_run (source, started desc);
