package eu.amadeos.tvapi.api;

import eu.amadeos.tvapi.crawl.CrawlRuns;
import eu.amadeos.tvapi.film.ChannelCount;
import eu.amadeos.tvapi.film.FilmPage;
import eu.amadeos.tvapi.film.FilmQuery;
import eu.amadeos.tvapi.film.FilmStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * The API TV asks, read-only and without login. Every answer may be cached for five minutes; an ETag
 * lets TV ask again cheaply ({@link WebConfig}).
 */
@RestController
@RequestMapping(path = "/tv/v1", produces = "application/json")
public class FilmController {
    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();
    private static final ZoneId BROADCAST_ZONE = ZoneId.of("Europe/Berlin");

    /** A page of films. */
    public record FilmsJson(long total, int page, int size, List<FilmJson> films) {
    }

    /** How the crawler is doing, per source. */
    public record StatusJson(String version, long films, List<CrawlRuns.Run> sources) {
    }

    private final FilmStore films;
    private final CrawlRuns runs;
    private final String version;

    public FilmController(FilmStore films, CrawlRuns runs, ObjectProvider<BuildProperties> build) {
        this.films = films;
        this.runs = runs;
        this.version = build.stream().map(BuildProperties::getVersion).findFirst().orElse("dev");
    }

    /**
     * {@code GET /tv/v1/films?q=tatort&channel=Das Erste&from=2026-10-01&minMinutes=60&page=0&size=50}:
     * {@code from} and {@code to} are days (Berlin time) or times in ISO 8601.
     */
    @GetMapping("/films")
    public ResponseEntity<FilmsJson> films(@RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "") String channel,
                                           @RequestParam(defaultValue = "") String topic,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Integer minMinutes,
                                           @RequestParam(required = false) Integer maxMinutes,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        FilmPage result = films.search(new FilmQuery(q, channel, topic, time(from, "from"), time(to, "to"),
                minMinutes == null ? null : Duration.ofMinutes(minMinutes),
                maxMinutes == null ? null : Duration.ofMinutes(maxMinutes), page, size));
        return ResponseEntity.ok().cacheControl(CACHE).body(new FilmsJson(result.total(), result.page(),
                result.size(), result.films().stream().map(FilmJson::of).toList()));
    }

    @GetMapping("/films/{id}")
    public ResponseEntity<FilmJson> film(@PathVariable UUID id) {
        return films.get(id).map(f -> ResponseEntity.ok().cacheControl(CACHE).body(FilmJson.of(f)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No film " + id));
    }

    @GetMapping("/channels")
    public ResponseEntity<List<ChannelCount>> channels() {
        return ResponseEntity.ok().cacheControl(CACHE).body(films.channels());
    }

    @GetMapping("/status")
    public StatusJson status() {
        return new StatusJson(version, films.count(), runs.latest());
    }

    /** A day ("2026-10-25", from its start in Berlin) or a time ("2026-10-25T18:00:00Z"). */
    static Instant time(String text, String name) {
        if (text == null || text.isBlank()) return null;
        try {
            return text.length() == 10 ? LocalDate.parse(text).atStartOfDay(BROADCAST_ZONE).toInstant()
                    : Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + ": a day (2026-10-25) or a time (2026-10-25T18:00:00Z)");
        }
    }
}
