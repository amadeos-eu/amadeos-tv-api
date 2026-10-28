package eu.amadeos.tvapi.crawl;

import eu.amadeos.tvapi.DatabaseTest;
import eu.amadeos.tvapi.model.CrawlRun;
import eu.amadeos.tvapi.model.Film;
import eu.amadeos.tvapi.model.FilmQuery;
import eu.amadeos.tvapi.service.CrawlRunService;
import eu.amadeos.tvapi.service.FilmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static eu.amadeos.tvapi.Fixtures.film;
import static org.junit.jupiter.api.Assertions.*;

class CrawlerTest extends DatabaseTest {
    static final Instant NOW = Instant.parse("2026-10-25T21:30:00Z");

    @Autowired
    FilmService films;
    @Autowired
    CrawlRunService runs;

    /** The 25th has a, b and c (c without video), every other day a again and d, which fails. */
    static final class FakeSource implements MediathekSource {
        final List<String> asked = new ArrayList<>();
        boolean dayFails;

        @Override
        public String id() {
            return "ard";
        }

        @Override
        public List<String> published(LocalDate day) throws IOException {
            if (dayFails) throw new IOException("programm-api.ard.de: HTTP 503");
            return day.getDayOfMonth() == 25 ? List.of("a", "b", "c") : List.of("a", "d");
        }

        @Override
        public List<Film> films(String id) throws IOException {
            asked.add(id);
            if (id.equals("d")) throw new IOException("HTTP 500");
            if (id.equals("c")) return List.of();
            return List.of(film(id, "Das Erste", "", "Film " + id, "", NOW, 30));
        }
    }

    Crawler crawler(MediathekSource source, Instant now) {
        return new Crawler(List.of(source), films, runs,
                new CrawlProperties(List.of("ard"), 2, Duration.ZERO, Duration.ofHours(24), Duration.ofSeconds(1)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void savesNewFilmsAndAsksOnlyForWhatWasNotSeenRecently() {
        FakeSource source = new FakeSource();
        Crawler crawler = crawler(source, NOW);
        assertTrue(crawler.crawl(source));
        assertEquals(List.of("a", "b", "c", "d"), source.asked);
        assertEquals(2, films.search(FilmQuery.all()).total());
        CrawlRun run = runs.latest().getFirst();
        assertEquals(2, run.filmsNew());
        assertNull(run.error());

        // An hour later only c (no video yet) and d (failed) are asked for again.
        source.asked.clear();
        assertTrue(crawler(source, NOW.plus(Duration.ofHours(1))).crawl(source));
        assertEquals(List.of("c", "d"), source.asked);

        // A day later (now the 26th and the 25th) everything is asked for again, a only once, and a and b
        // are updated.
        source.asked.clear();
        crawler(source, NOW.plus(Duration.ofHours(25))).crawl(source);
        assertEquals(List.of("a", "d", "b", "c"), source.asked);
        assertEquals(2, runs.latest().getFirst().filmsUpdated());
    }

    @Test
    void aFailingSourceIsNotedAndTheExitCodeSaysSo() throws Exception {
        FakeSource source = new FakeSource();
        source.dayFails = true;
        Crawler crawler = crawler(source, NOW);
        crawler.run(null);
        assertEquals(1, crawler.getExitCode());
        assertEquals("programm-api.ard.de: HTTP 503", runs.latest().getFirst().error());
    }

    @Test
    void anUnknownSourceIsAnError() throws Exception {
        Crawler crawler = new Crawler(List.of(new FakeSource()), films, runs,
                new CrawlProperties(List.of("zdf"), 1, Duration.ZERO, Duration.ofHours(24), Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        crawler.run(null);
        assertEquals(1, crawler.getExitCode());
        assertTrue(runs.latest().isEmpty());
    }
}
