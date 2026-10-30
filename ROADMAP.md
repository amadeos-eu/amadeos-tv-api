# amadeos-tv-api roadmap

What is open comes first, then what has been achieved, newest first. The design and the original steps
are in [docs/design.md](docs/design.md).

Status date: 2026-10-28

## Open

### 1. Official access to the broadcasters' data

The sources use the services that the broadcasters' own pages and apps use: ARD's without a key, ZDF's
with the token that zdf.de hands to its pages. Both broadcasters have a developer portal that wants a
registration. Before amadeOS TV goes public, the sources move to the registered access:

- [ ] **ARD:** register at [developer.ard.de](https://developer.ard.de/). Find out which APIs it covers
  (programme, Mediathek items, streams) and its terms: may a third-party program show the films with
  links to ARD's videos, rate limits, attribution, private or commercial use.
- [ ] **ZDF:** register at [developer.zdf.de](https://developer.zdf.de/), with the same questions.
- [ ] Move `ArdSource` and `ZdfSource` to the official APIs. The keys go into
  `/etc/amadeos-tv-api/application.yml`; the Ansible role in datazuul-devops keeps them in
  `ansible/secrets/`, like the database password.
- [ ] Note in the README and `docs/design.md` which terms apply to the data and the links.

Until then, production serves the ARD films for testing TV.

### 2. ZDF delivers no films

Found in the first live run (2026-10-28, v1.0.0):

- On the server, the ZDF run finished in under a second, without an error and without films: the day
  listing seems to come back empty there.
- Locally, the same jar lists 140 items for the day, but every film document
  (`api.zdf.de/content/documents/<id>.json`) answers HTTP 500, also on the retry.

This is not debugged further on the unofficial API; it is solved by the official access (item 1).
Meanwhile `zdf` can be taken out of `amadeos_tv_api_sources` in the Ansible defaults.

### 3. Crawler

- [ ] A source that lists no items at all counts as a failed run, so that `/status` shows it and the
  crawler ends with exit code 1 (now an empty ZDF run looks successful).
- [ ] A quieter log: jOOQ's tips and logo off (`org.jooq.no-tips`, `org.jooq.no-logo`), its warning
  about the PostgreSQL version (the free edition lists only the newest versions; the queries run on
  PostgreSQL 17, as the tests show) at `ERROR`, and a 404 for a single item at `INFO` instead of `WARN`.

### 4. TV

- [ ] The Library page of TV, Watch later, Follow topic and Downloads, plus the handbooks (step 6 of the
  design).
- [ ] TV sends its own User-Agent (for example `amade-video/<version>`): nginx on api.amadeos.eu turns
  away `Java/`, `curl` and other tools by their User-Agent.

### 5. Later

- [ ] ARTE (after asking ARTE), ORF, SRF.

## Achieved

### 2026-10-28: v1.0.0 live on api.amadeos.eu

- Deployed with `deploy-api.amadeos.eu.yml` of datazuul-devops (PostgreSQL 17, Let's Encrypt, nginx,
  hourly `amadeos-tv-crawl.timer`).
- First crawl: 3 days, 6 minutes, ARD 1084 films; ZDF none (see item 2). `/tv/v1/status` and the search
  checked in the browser.
- jOOQ instead of JDBC, and the code layered into controller, service, repository and model
  ([#3](https://github.com/amadeos-eu/amadeos-tv-api/pull/3)).

### Before

- The TV API with the ARD and ZDF Mediatheken: skeleton, both sources, search API, deploy files
  ([#1](https://github.com/amadeos-eu/amadeos-tv-api/pull/1)); separate nginx zones for the rate limit
  and the cache ([#2](https://github.com/amadeos-eu/amadeos-tv-api/pull/2)).
