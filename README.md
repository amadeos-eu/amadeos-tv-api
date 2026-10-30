# amadeos-tv-api

The service behind the Library page of [TV](https://github.com/amadeos-eu/amade-tv) for amadeOS. It
collects the on-demand films of the German public broadcasters' media libraries once, for everyone,
and answers TV's searches. Only metadata and links are kept: every video stays on the broadcaster's
server, and nothing a broadcaster limits to a country is unlocked.

Status: **v1.0.0 runs on api.amadeos.eu for testing.** ARD (Das Erste, the regional broadcasters,
ONE, ARD-alpha, tagesschau24) and ZDF (ZDF, ZDFneo, ZDFinfo, ZDFtivi) are crawled; ZDF delivers no
films at the moment, and both sources still have to move to the broadcasters' registered APIs. Next
steps are in [ROADMAP.md](ROADMAP.md), the design in [docs/design.md](docs/design.md).

## One jar, two modes

| Mode | Started by | What it does |
|------|------------|--------------|
| API (default) | `amadeos-tv-api.service` | REST API on port 8085, behind nginx |
| Crawler (`--spring.profiles.active=crawler`) | `amadeos-tv-crawl.timer`, hourly | one run, then exit code 0, or 1 if a source failed |

Both use the same PostgreSQL database. Flyway creates and updates the tables when the program starts.

### The crawler

For each source and each of the last `crawl.days` days (in Berlin time), the crawler asks:

1. which items were published that day (one request per day), and
2. for the items it has not seen in the last `crawl.refresh-after`, the films themselves (one request
   per item).

An hourly run is therefore cheap: only what is new is asked for. Requests to one host go one at a
time with `crawl.pause` between them, under the User-Agent `amadeos-tv-api/<version> (+https://amadeos.eu)`.
A busy server (429, 5xx) is asked once more after a pause. Each run is noted in the table
`crawl_run` and shown by `/tv/v1/status`.

| Source | Day list | Film |
|--------|----------|------|
| `ard` | `programm-api.ard.de/program/api/program?day=…` (the program service TV's guide uses too) | `api.ardmediathek.de/page-gateway/pages/ard/item/<id>`: MP4 in normal and HD quality, HLS, subtitles. Versions with audio description or sign language become films of their own |
| `zdf` | `api.zdf.de/search/documents?hasVideo=true&types=page-video&from=…&to=…` (all its pages) | `api.zdf.de/content/documents/<id>.json`, then the "ptmd" answer it points to: MP4 up to 1080 lines, HLS, subtitles, audio description, and the sign-language version |

ZDF's API wants the token zdf.de gives its own pages (`Api-Auth: Bearer …`). The crawler reads it from
www.zdf.de at the start of a run, and again once when ZDF stops taking it. Should ZDF change its page so
that no token is found, the ZDF run fails visibly; the tokens can then be set by hand in
`crawl.zdf.search-token` and `crawl.zdf.video-token` until the source is fixed. 3sat, KiKA, phoenix and
ARTE also show up in ZDF's search but are left out there: they come from ARD or get sources of their own.

## API

`/tv/v1/`, JSON, read-only, no login. Every answer may be cached for five minutes (`Cache-Control`)
and has an `ETag`; with `If-None-Match` an unchanged answer is a short 304.

| Call | Answer |
|------|--------|
| `GET /tv/v1/films?q=&channel=&topic=&from=&to=&minMinutes=&maxMinutes=&page=&size=` | `{total, page, size, films: [...]}`. Newest first, or best hits first when `q` is given; `size` at most 100 |
| `GET /tv/v1/films/{id}` | one film |
| `GET /tv/v1/channels` | `[{source, channel, films}]` |
| `GET /tv/v1/status` | version, number of films, the last run of each source |

- `q` takes words as people type them: `tatort -münster "am sonntag"`. The search uses PostgreSQL's
  German full-text search, so "Frachters" finds "Frachter".
- Topic and title weigh more than the description.
- `from` and `to` are days (`2026-10-25`, Berlin time) or times (`2026-10-25T18:00:00Z`).

A film:

```json
{
  "id": "6f1c…", "source": "ard", "channel": "Das Erste", "topic": "Tatort", "title": "Tod am Deich",
  "description": "…", "publishedAt": "2026-10-25T19:15:00Z", "durationSeconds": 5310,
  "availableUntil": "2026-11-24T22:59:00Z", "geo": "DE",
  "websiteUrl": "https://www.ardmediathek.de/video/…", "subtitleUrl": "https://….vtt",
  "videoSd": "https://….mp4", "videoHd": "https://….mp4", "videoHls": "https://….m3u8",
  "audioDescription": false, "signLanguage": false
}
```

## Building and testing

Requirements: Java 25 JDK, and Docker (Testcontainers starts PostgreSQL 17) for the tests and for the
build itself: the jOOQ classes of the tables are generated from a database that Flyway created from
`src/main/resources/db/migration` (`./gradlew generateJooq`, which runs again only when a migration changed).

The code is layered: `controller` (REST API), `service`, `repository` (jOOQ, with the generated classes in
`repository.jooq`) and `model` (the records they pass along); `crawl` holds the crawler and its sources.

```bash
./gradlew test       # unit tests and database tests; nothing goes online
./gradlew bootJar    # build/libs/amadeos-tv-api-0.0.0-dev.0.jar
```

To try it locally:

```bash
docker run -d --name tvdb -e POSTGRES_USER=tvapi -e POSTGRES_PASSWORD=secret -e POSTGRES_DB=tvapi -p 5432:5432 postgres:17-alpine
TVAPI_DB_PASSWORD=secret java -jar build/libs/amadeos-tv-api-*.jar --spring.profiles.active=crawler --crawl.days=1
TVAPI_DB_PASSWORD=secret java -jar build/libs/amadeos-tv-api-*.jar
curl 'localhost:8085/tv/v1/films?q=tatort'
```

## Running it on a server

`deploy/` has what the server needs:

- `amadeos-tv-api.service`: the API,
- `amadeos-tv-crawl.service` and `.timer`: the crawler, every hour,
- `application.yml`: an example of `/etc/amadeos-tv-api/application.yml`, with the database password,
- `nginx-tv-api.conf`: the location with a rate limit and a short cache.

In short:

1. Create a PostgreSQL user and database `tvapi`, and a system user `tvapi`.
2. Copy the jar from the GitHub release to `/opt/amadeos-tv-api/amadeos-tv-api.jar`.
3. Copy the units to `/etc/systemd/system/`.
4. Run `systemctl enable --now amadeos-tv-api.service amadeos-tv-crawl.timer`.

The Ansible role for the amadeos.eu server follows.

## License

EUPL-1.2. The data belongs to the broadcasters. This service only points to it.
