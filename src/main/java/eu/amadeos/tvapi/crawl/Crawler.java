package eu.amadeos.tvapi.crawl;

import eu.amadeos.tvapi.film.Film;
import eu.amadeos.tvapi.film.FilmStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One run of the crawler (profile {@code crawler}): for each source, the days from today back, and of the
 * items published on them the ones not seen recently. A source that fails is noted in its run and the
 * others go on; the program then ends with exit code 1, so that the systemd unit shows the failure.
 */
@Component
@Profile("crawler")
public class Crawler implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(Crawler.class);
    /** Days are days in the broadcasters' time zone. */
    static final ZoneId BROADCAST_ZONE = ZoneId.of("Europe/Berlin");

    private final Map<String, MediathekSource> sources;
    private final FilmStore films;
    private final CrawlRuns runs;
    private final CrawlProperties properties;
    private final Clock clock;
    private int exitCode;

    public Crawler(List<MediathekSource> sources, FilmStore films, CrawlRuns runs, CrawlProperties properties,
                   Clock clock) {
        this.sources = sources.stream().collect(Collectors.toMap(MediathekSource::id, Function.identity()));
        this.films = films;
        this.runs = runs;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String id : properties.sources()) {
            MediathekSource source = sources.get(id);
            if (source == null) {
                LOG.error("No source {}; known are {}", id, sources.keySet());
                exitCode = 1;
                continue;
            }
            if (!crawl(source)) exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** One source; false if it failed. */
    boolean crawl(MediathekSource source) {
        Instant started = clock.instant();
        long run = runs.start(source.id(), started);
        int added = 0, updated = 0, failedItems = 0;
        String error = null;
        try {
            LocalDate today = LocalDate.ofInstant(started, BROADCAST_ZONE);
            for (int d = 0; d < properties.days(); d++) {
                List<String> ids = source.published(today.minusDays(d));
                Set<String> fresh = films.seenSince(source.id(), ids, started.minus(properties.refreshAfter()));
                List<String> wanted = new ArrayList<>(ids);
                wanted.removeAll(fresh);
                LOG.info("{} {}: {} items, {} to ask for", source.id(), today.minusDays(d), ids.size(), wanted.size());
                for (String id : wanted) {
                    try {
                        for (Film f : source.films(id)) {
                            if (!f.hasVideo()) continue;
                            if (films.save(f, clock.instant()) == FilmStore.Saved.NEW) added++;
                            else updated++;
                        }
                    } catch (IOException e) {
                        // One item that does not load is no reason to give up the day.
                        LOG.warn("{} item {}: {}", source.id(), id, e.getMessage());
                        failedItems++;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("{} failed", source.id(), e);
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        runs.finish(run, clock.instant(), added, updated, error);
        LOG.info("{}: {} new, {} updated, {} items failed{}", source.id(), added, updated, failedItems,
                error == null ? "" : ", run failed: " + error);
        return error == null;
    }
}
