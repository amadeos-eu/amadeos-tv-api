package eu.amadeos.tvapi.crawl.zdf;

import eu.amadeos.tvapi.Fixtures;
import eu.amadeos.tvapi.crawl.HttpStatusException;
import eu.amadeos.tvapi.model.Film;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZdfSourceTest {
    static final String DUO = "das-duo-tod-am-deich-100";
    static final String PTMD = "https://api.zdf.de/tmd/2/android_native_5/vod/ptmd/mediathek/";

    /** The answers by address; what was asked, with the token it was asked with. */
    final Map<String, String> answers = new HashMap<>();
    final List<String> requests = new ArrayList<>();
    final List<String> tokens = new ArrayList<>();
    int unauthorized;

    ZdfSource source(ZdfProperties properties) {
        answer(ZdfSource.START_PAGE, "zdf/start.html");
        return new ZdfSource((url, headers) -> {
            requests.add(url);
            tokens.add(headers.getOrDefault("Api-Auth", ""));
            if (unauthorized > 0 && !url.equals(ZdfSource.START_PAGE)) {
                unauthorized--;
                throw new HttpStatusException(url, 401);
            }
            String fixture = answers.get(url);
            if (fixture == null) throw new HttpStatusException(url, 404);
            return Fixtures.read(fixture);
        }, JsonMapper.builder().build(), properties);
    }

    ZdfSource source() {
        return source(new ZdfProperties("", ""));
    }

    void answer(String url, String fixture) {
        answers.put(url, fixture);
    }

    @Test
    void theTokensComeFromTheStartPage() throws IOException {
        assertEquals(new ZdfSource.Tokens("searchtoken0123456789abcdef", "videotoken0123456789abcdef"),
                ZdfSource.tokens(Fixtures.read("zdf/start.html")));
        String nextPage = "<script>{\\\"appToken\\\":{\\\"apiToken\\\":\\\"onetoken0123456789abcdef\\\"}}</script>";
        assertEquals(new ZdfSource.Tokens("onetoken0123456789abcdef", "onetoken0123456789abcdef"),
                ZdfSource.tokens(nextPage));
        assertThrows(IOException.class, () -> ZdfSource.tokens("<html></html>"));
    }

    @Test
    void aDayIsMidnightToMidnightInBerlin() {
        assertTrue(ZdfSource.dayUrl(LocalDate.of(2026, 7, 1))
                .contains("&from=2026-07-01T00:00:00.000%2B02:00&to=2026-07-01T23:59:59.999%2B02:00&"));
        // The clocks go back on 25 October.
        assertTrue(ZdfSource.dayUrl(LocalDate.of(2026, 10, 25))
                .contains("&from=2026-10-25T00:00:00.000%2B02:00&to=2026-10-25T23:59:59.999%2B01:00&"));
        assertTrue(ZdfSource.dayUrl(LocalDate.of(2026, 11, 1))
                .contains("&from=2026-11-01T00:00:00.000%2B01:00&to=2026-11-01T23:59:59.999%2B01:00&"));
    }

    @Test
    void aDayListsEachPageWithAVideoOnceOverAllSearchPages() throws IOException {
        LocalDate day = LocalDate.of(2026, 10, 25);
        String page2 = "https://api.zdf.de/search/documents?hasVideo=true&q=*&types=page-video&sortOrder=desc"
                + "&from=2026-10-25T00%3A00%3A00.000%2B02%3A00&page=2";
        answer(ZdfSource.dayUrl(day), "zdf/day-1.json");
        answer(page2, "zdf/day-2.json");

        List<String> ids = source().published(day);

        assertEquals(List.of(DUO, "heute-journal-vom-25-oktober-2026-100", "terra-x-die-ostsee-102"), ids);
        assertEquals(List.of(ZdfSource.START_PAGE, ZdfSource.dayUrl(day), page2), requests);
        assertEquals("Bearer searchtoken0123456789abcdef", tokens.get(1));
    }

    @Test
    void aFilmHasTheMainVersionAndTheOnesWithAudioDescriptionAndSignLanguage() throws IOException {
        answer("https://api.zdf.de/content/documents/" + DUO + ".json", "zdf/document.json");
        answer(PTMD + "261025_duo_neo/3", "zdf/ptmd.json");
        answer(PTMD + "261025_duo_neo_dgs/1", "zdf/ptmd-dgs.json");

        List<Film> films = source().films(DUO);

        assertEquals(List.of(ZdfSource.START_PAGE, "https://api.zdf.de/content/documents/" + DUO + ".json",
                PTMD + "261025_duo_neo/3", PTMD + "261025_duo_neo_dgs/1"), requests);
        assertEquals("Bearer videotoken0123456789abcdef", tokens.get(1));
        assertEquals(3, films.size());

        Film main = films.get(0);
        assertEquals("zdf", main.source());
        assertEquals(DUO, main.sourceId());
        assertEquals("ZDFneo", main.channel());
        assertEquals("Das Duo", main.topic());
        assertEquals("Das Duo - Tod am Deich (S02/E05)", main.title());
        assertEquals("Ein Frachter läuft ein, und an Bord fehlt der Kapitän.", main.description());
        assertEquals(Instant.parse("2026-10-25T19:15:00Z"), main.publishedAt());
        assertEquals(Instant.parse("2026-11-24T22:59:00Z"), main.availableUntil());
        assertEquals(Duration.ofSeconds(5280), main.duration());
        assertEquals("DE-AT-CH", main.geo());
        assertEquals("https://www.zdf.de/filme/das-duo/das-duo-tod-am-deich-100.html", main.websiteUrl());
        assertEquals("https://utstreaming.zdf.de/duo/sub.vtt", main.subtitleUrl());
        // MP4 only, German, no more than 1080 lines.
        assertEquals("https://zdf.example/duo/540.mp4", main.videoSd());
        assertEquals("https://zdf.example/duo/1080.mp4", main.videoHd());
        assertEquals("https://zdf.example/duo/master.m3u8", main.videoHls());
        assertFalse(main.audioDescription());
        assertFalse(main.signLanguage());

        Film ad = films.get(1);
        assertEquals(DUO + "-ad", ad.sourceId());
        assertEquals("Das Duo - Tod am Deich (S02/E05) (Audiodeskription)", ad.title());
        assertEquals("https://zdf.example/duo/540-ad.mp4", ad.videoSd());
        assertEquals("https://zdf.example/duo/720-ad.mp4", ad.videoHd());
        assertEquals("https://zdf.example/duo/master-ad.m3u8", ad.videoHls());
        assertTrue(ad.audioDescription());

        Film dgs = films.get(2);
        assertEquals(DUO + "-dgs", dgs.sourceId());
        assertEquals("Das Duo - Tod am Deich (S02/E05) (Gebärdensprache)", dgs.title());
        assertEquals("https://zdf.example/duo/dgs-360.mp4", dgs.videoSd());
        assertEquals("https://zdf.example/duo/dgs-720.mp4", dgs.videoHd());
        assertEquals("", dgs.videoHls());
        assertTrue(dgs.signLanguage());
    }

    @Test
    void aFilmWithoutBroadcastTakesWhatTheDocumentAndTheVideosSay() throws IOException {
        answer("https://api.zdf.de/content/documents/terra-x-die-ostsee-102.json", "zdf/document-plain.json");
        answer(PTMD + "261025_ostsee", "zdf/ptmd-plain.json");

        Film film = source().films("terra-x-die-ostsee-102").getFirst();

        assertEquals("ZDF", film.channel());
        assertEquals("Terra X", film.topic());
        assertEquals("Die Ostsee", film.title());
        assertEquals("Ein Meer im Wandel.", film.description());
        assertEquals(Instant.parse("2026-10-25T16:00:00Z"), film.publishedAt());
        assertEquals(Duration.ofMinutes(44), film.duration());
        assertEquals("", film.geo());
        assertEquals("https://zdf.example/ostsee/540.mp4", film.videoSd());
        assertEquals("", film.videoHd());
    }

    @Test
    void otherChannelsAreLeftToTheirOwnLibraries() throws IOException {
        answer("https://api.zdf.de/content/documents/kulturzeit-100.json", "zdf/document-3sat.json");

        assertEquals(List.of(), source().films("kulturzeit-100"));
        assertEquals(2, requests.size());
    }

    @Test
    void aTokenThatIsNotTakenAnyMoreIsReadAgainOnce() throws IOException {
        LocalDate day = LocalDate.of(2026, 10, 26);
        answer(ZdfSource.dayUrl(day), "zdf/day-2.json");
        unauthorized = 1;

        assertEquals(2, source().published(day).size());
        assertEquals(List.of(ZdfSource.START_PAGE, ZdfSource.dayUrl(day), ZdfSource.START_PAGE, ZdfSource.dayUrl(day)),
                requests);

        unauthorized = 2;
        requests.clear();
        assertThrows(HttpStatusException.class, () -> source().published(day));
    }

    @Test
    void tokensSetByHandSpareThePage() throws IOException {
        LocalDate day = LocalDate.of(2026, 10, 26);
        answer(ZdfSource.dayUrl(day), "zdf/day-2.json");

        source(new ZdfProperties("mysearchtoken", "myvideotoken")).published(day);

        assertEquals(List.of(ZdfSource.dayUrl(day)), requests);
        assertEquals("Bearer mysearchtoken", tokens.getFirst());
    }
}
