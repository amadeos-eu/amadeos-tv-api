package eu.amadeos.tvapi.crawl.ard;

import eu.amadeos.tvapi.Fixtures;
import eu.amadeos.tvapi.model.Film;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArdSourceTest {
    static final String TATORT = "Y3JpZDovL2Rhc2Vyc3RlLmRlL3RhdG9ydC80NTY";
    final List<String> requests = new ArrayList<>();

    ArdSource source(String answer) {
        return new ArdSource((url, headers) -> {
            requests.add(url);
            return Fixtures.read(answer);
        }, JsonMapper.builder().build());
    }

    @Test
    void aDayListsEachItemWithAVideoOnce() throws IOException {
        List<String> ids = source("ard/day.json").published(LocalDate.of(2026, 10, 25));
        assertEquals(List.of("Y3JpZDovL3RhZ2Vzc2NoYXUuZGUvMTIz", TATORT, "Y3JpZDovL3dkci5kZS83ODk"), ids);
        assertEquals(List.of("https://programm-api.ard.de/program/api/program?day=2026-10-25"), requests);
    }

    @Test
    void anItemHasTheMainVersionAndTheOnesWithAudioDescriptionAndSignLanguage() throws IOException {
        List<Film> films = source("ard/item.json").films(TATORT);
        assertEquals(List.of("https://api.ardmediathek.de/page-gateway/pages/ard/item/" + TATORT
                + "?embedded=true&mcV6=true"), requests);
        assertEquals(3, films.size());

        Film main = films.get(0);
        assertEquals("ard", main.source());
        assertEquals(TATORT, main.sourceId());
        assertEquals("Das Erste", main.channel());
        assertEquals("Tatort", main.topic());
        assertEquals("Tod am Deich", main.title());
        assertEquals("Ein Frachter läuft ein, und an Bord fehlt der Kapitän.", main.description());
        assertEquals(Instant.parse("2026-10-25T19:15:00Z"), main.publishedAt());
        assertEquals(Instant.parse("2026-11-24T22:59:00Z"), main.availableUntil());
        assertEquals(Duration.ofSeconds(5310), main.duration());
        assertEquals("DE", main.geo());
        assertEquals("https://www.ardmediathek.de/video/" + TATORT, main.websiteUrl());
        assertEquals("https://example.ard.de/tatort/sub.vtt", main.subtitleUrl());
        // The best German one under 720 lines, the best German one from 720 on; not the English version.
        assertEquals("https://example.ard.de/tatort/540.mp4", main.videoSd());
        assertEquals("https://example.ard.de/tatort/1080.mp4", main.videoHd());
        assertEquals("https://example.ard.de/tatort/master.m3u8", main.videoHls());
        assertFalse(main.audioDescription());

        Film ad = films.get(1);
        assertEquals(TATORT + "-ad", ad.sourceId());
        assertEquals("Tod am Deich (Audiodeskription)", ad.title());
        assertTrue(ad.audioDescription());
        assertEquals("https://example.ard.de/tatort/ad-720.mp4", ad.videoHd());
        assertEquals("", ad.videoSd());

        Film dgs = films.get(2);
        assertEquals(TATORT + "-dgs", dgs.sourceId());
        assertTrue(dgs.signLanguage());
        assertEquals("https://example.ard.de/tatort/dgs-540.mp4", dgs.videoSd());
    }

    @Test
    void anItemWithoutVideoHasNoFilm() throws IOException {
        assertEquals(List.of(), source("ard/item-no-video.json").films("x"));
    }

    @Test
    void addressesWithoutSchemeGetHttps() {
        assertEquals("https://example.org/a.mp4", ArdSource.https("//example.org/a.mp4"));
        assertEquals("http://example.org/a.mp4", ArdSource.https("http://example.org/a.mp4"));
    }

    @Test
    void anAnswerThatIsNotJsonIsAnError() {
        ArdSource source = new ArdSource((url, headers) -> "<html>maintenance</html>", JsonMapper.builder().build());
        assertThrows(IOException.class, () -> source.published(LocalDate.of(2026, 10, 25)));
    }
}
