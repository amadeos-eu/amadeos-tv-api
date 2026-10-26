# amadeos-tv-api: design draft

Status: design of 2026-10-26; the first steps (skeleton, ARD, ZDF, search API) are built.

## Purpose

The Library page in TV needs the on-demand programs of the public broadcasters. TV does not use
MediathekView's film list (the request to the MediathekView team is still open). Instead, a small
service on Ralf's server collects the data once for everyone. TV only asks that service.

- The broadcasters get one crawler instead of one per TV installation.
- TV starts fast and searches over the network in milliseconds, with no 600,000-entry index on every
  machine.
- The service only stores metadata and links. Every video stays on the broadcasters' servers, and
  nothing that is geo-blocked is unlocked.

## One jar, two modes

Spring Boot 4.1, Java 25, Gradle like the other repos, package `eu.amadeos.tvapi`.

| Mode | Started by | What it does |
|---|---|---|
| API (default) | systemd service, behind nginx | REST API, read-only on the database |
| Crawler (`--spring.profiles.active=crawler`, `spring.main.web-application-type=none`) | systemd timer (or cron) | one crawl run, then it exits with 0, or 1 if a source failed |

Both modes share the domain, the repositories and the Flyway migrations. Running the crawler as a
command line program with a timer instead of `@Scheduled` inside the API keeps the API small. A
crawl that hangs or eats memory never takes the API down, and the timer shows each run in
`journalctl`.

Timers:

- **hourly**: the recent days of every source (see below),
- **nightly** (03:30): the topic pages of every source, plus a check of which films are gone.

## Sources

Each source is a class behind one interface:

```java
interface MediathekSource {
    String id();                          // "ard", "zdf", "arte"
    Stream<Film> recent(LocalDate day);   // what was published on that day
    Stream<Film> topics();                // the full catalogue, for the nightly run
}
```

What the broadcasters' own pages use today. I checked this in the source code of MServer, the
MediathekView crawler, as a reference only (GPL-3.0; no code is taken over). The crawler has to be
tried live on the server, because my sandbox cannot reach these hosts.

| Source | Recent days | Full catalogue | Video links | Notes |
|---|---|---|---|---|
| **ARD** (Das Erste, the regional channels, ONE, alpha, tagesschau24, funk partly) | `programm-api.ard.de/program/api/program?day=…&channelIds=…&mode=channel`, the same API TV's guide already uses; items carry the Mediathek id | `api.ardmediathek.de/page-gateway/pages/<channel>/editorial/experiment-a-z`, then the compilation widgets | `api.ardmediathek.de/page-gateway/pages/ard/item/<id>` (MP4 in several qualities, HLS, subtitles) | no key |
| **ZDF** (ZDF, ZDFneo, ZDFinfo, ZDFtivi) | `api.zdf.de/search/documents?hasVideo=true&from=…&to=…&sortBy=date` | GraphQL `api.zdf.de/graphql` with persisted queries (fragile: the hashes change with ZDF's website) | `content/documents/<id>.json`, then the "ptmd" template | needs the `Api-Auth` token from the zdf.de page; TV already reads it for the guide. 3sat, KiKA, phoenix and ARTE are left out here (ARD or own sources) |
| **ARTE** | OPA v3 `api.arte.tv/api/opa/v3/videos?sort=-videoRightsBegin&language=de` | the same, paged | `OPA/v3/streams/<programId>/…` | needs a bearer token that is built into ARTE's own players. Only with ARTE's OK, so it comes in a later step |
| later | ORF, SRF, DW, KiKA directly | | | each one separately, step by step |

Politeness rules, the same as in TV:

- At most 2 requests at a time per host, with a pause between pages.
- `User-Agent: amadeos-tv-api/<version> (+https://amadeos.eu)`.
- Answers are kept for the run, so the same page is never asked twice.
- On a 4xx or 5xx the source backs off and is marked failed for this run. The other sources go on.

## Data

PostgreSQL 16 or newer, Flyway migrations.

```
film(id uuid, source, channel, topic, title, description, published_at, duration_s,
     available_until, geo, website_url, subtitle_url, video_sd, video_hd, video_hls,
     audio_description bool, sign_language bool, first_seen, last_seen, gone_at,
     search tsvector generated from topic/title/description with the german config)
channel(id, name, source)
crawl_run(id, mode, source, started, finished, films_new, films_updated, films_gone, error)
```

- A film's key is (source, broadcaster id). When there is no stable id, a hash of the video URL is
  used.
- Films that are no longer listed get `gone_at`; they are deleted after 30 days.
- Full-text search uses a GIN index on `search`, with `websearch_to_tsquery('german', …)` for
  queries typed by the user. Several hundred thousand rows are no problem for this.

## API

`https://api.amadeos.eu/tv/v1/` (or a subdomain of your choice), JSON, read-only, no login.

| Call | Answer |
|---|---|
| `GET /films?q=&channel=&topic=&from=&to=&minDuration=&maxDuration=&page=&size=` | page of films, newest first, at most 100 per page |
| `GET /films/new?since=2026-10-25T00:00:00Z` | everything new or changed since then, for "new episodes of followed topics" |
| `GET /films/{id}` | one film with all links |
| `GET /topics?channel=&q=` | topic names with counts, for "Follow topic" |
| `GET /channels` | channels with their film counts |
| `GET /status` | last run per source, film count, version |

- Every answer has `ETag` and `Cache-Control: max-age=300`, and nginx caches GET answers for a
  few minutes.
- A rate limit per IP in nginx (for example 10 requests/s with a burst of 30).
- An OpenAPI description is served at `/tv/v1/openapi.json`, generated by springdoc.

## How TV uses it

- **Library page**: a search field and filters, which call `/films`. There is no local copy of the
  list. The last answers are cached on disk, so the page is not empty when you are offline.
- **Watch later** and **Follow topic** are stored locally in TV, like the reminders. TV checks
  `/films/new` once an hour for followed topics.
- **Downloads**: MP4 over HTTP with resume, HLS with ffmpeg, like the recordings, into
  `~/Videos/TV/<topic>/`.
- The API address is a setting with the default `https://api.amadeos.eu/tv/v1`, so anyone can run
  their own instance.

## Running it

- An Ansible role next to the amadeos.eu playbook:
  - Java 25 runtime, PostgreSQL, a user `tvapi`,
  - `/opt/amadeos-tv-api/amadeos-tv-api.jar`,
  - `amadeos-tv-api.service` (the API), and `amadeos-tv-crawl.service` with two timers (hourly
    and nightly),
  - an nginx site with TLS (certbot as for amadeos.eu).
- Configuration in `/etc/amadeos-tv-api/application.yml` (database password, sources on or off,
  request pauses).
- Logs go to the journal. `/status` shows whether the crawler is healthy, and a failed run makes
  the timer unit fail visibly in `systemctl --failed`.

## Legal

- Publishing titles, descriptions and links of the broadcasters is what MediathekView,
  MediathekViewWeb and others have done for years. The broadcasters have not given official
  permission, but have not objected either. We host no videos and get around no geo-blocks.
- The descriptions are the broadcasters' texts. The API passes them on unchanged and with the link
  to the broadcaster's page (`website_url`), so it is always clear where they come from.
- The site needs a legal notice (Impressum) and a privacy notice: no logins and no tracking; nginx
  keeps access logs for at most 7 days.

## Steps

1. **Skeleton**:
   - the Gradle project, the two modes, Flyway schema, `/status` and `/channels`,
   - Testcontainers tests with Postgres,
   - CI on GitHub Actions (build, tests, jar as a release asset).
2. **ARD source**: recent days and topics, video links. Tests against recorded answers.
3. **ZDF source**, the same way.
4. **Search API**: `/films`, `/films/new`, `/topics`, ETag.
5. **Ansible role and the first live run on the server** (by Ralf, with me fixing what the real
   answers show).
6. **TV**: the Library page, Watch later, Follow topic and Downloads, plus the handbooks.
7. Later: ARTE (after asking ARTE), ORF, SRF.
