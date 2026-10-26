package eu.amadeos.tvapi.film;

import eu.amadeos.tvapi.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static eu.amadeos.tvapi.Fixtures.film;
import static org.junit.jupiter.api.Assertions.*;

class FilmStoreTest extends DatabaseTest {
    static final Instant T = Instant.parse("2026-10-25T20:00:00Z");

    @Autowired
    FilmStore store;

    void fill() {
        store.save(film("a", "Das Erste", "Tatort", "Tod am Deich", "Ein Frachter läuft in den Hafen ein.",
                T.minusSeconds(86400), 90), T);
        store.save(film("b", "WDR", "Lokalzeit", "Lokalzeit aus Köln", "Nachrichten aus der Stadt, ein Tod im Rhein und das Wetter.",
                T.minusSeconds(3600), 30), T);
        store.save(film("c", "Das Erste", "", "Hafenkante", "Krimiserie am Hamburger Hafen.", T, 45), T);
    }

    @Test
    void savingAgainUpdates() {
        assertEquals(FilmStore.Saved.NEW, store.save(film("a", "Das Erste", "Tatort", "Alt", "", T, 90), T));
        assertEquals(FilmStore.Saved.UPDATED, store.save(film("a", "Das Erste", "Tatort", "Neu", "", T, 90),
                T.plusSeconds(60)));
        FilmPage all = store.search(FilmQuery.all());
        assertEquals(1, all.total());
        Film f = all.films().getFirst();
        assertEquals("Neu", f.title());
        assertNotNull(f.id());
        assertEquals(Duration.ofMinutes(90), f.duration());
        assertEquals(f, store.get(f.id()).orElseThrow());
    }

    @Test
    void searchFindsGermanWordFormsAndRanksTitlesFirst() {
        fill();
        // "Frachters" finds "Frachter": the German stemmer.
        assertEquals(List.of("Tod am Deich"), titles("Frachters"));
        // A word in the title weighs more than the same word in a description.
        assertEquals(List.of("Tod am Deich", "Lokalzeit aus Köln"), titles("tod"));
        assertEquals(2, store.search(query("hafen")).total());
        assertEquals(List.of("Lokalzeit aus Köln"), titles("wetter -tatort"));
        assertEquals(List.of("Hafenkante"), titles("\"hamburger hafen\""));
        assertEquals(0, store.search(query("sportschau")).total());
    }

    @Test
    void filtersAndPages() {
        fill();
        assertEquals(List.of("Hafenkante", "Lokalzeit aus Köln", "Tod am Deich"),
                store.search(FilmQuery.all()).films().stream().map(Film::title).toList());
        assertEquals(2, store.search(new FilmQuery("", "das erste", "", null, null, null, null, 0, 50)).total());
        assertEquals(1, store.search(new FilmQuery("", "", "Tatort", null, null, null, null, 0, 50)).total());
        assertEquals(2, store.search(new FilmQuery("", "", "", T.minusSeconds(7200), null, null, null, 0, 50)).total());
        assertEquals(1, store.search(new FilmQuery("", "", "", null, null, Duration.ofMinutes(60), null, 0, 50)).total());
        assertEquals(1, store.search(new FilmQuery("", "", "", null, null, null, Duration.ofMinutes(30), 0, 50)).total());
        FilmPage second = store.search(new FilmQuery("", "", "", null, null, null, null, 1, 2));
        assertEquals(3, second.total());
        assertEquals(List.of("Tod am Deich"), second.films().stream().map(Film::title).toList());
        assertEquals(FilmQuery.MAX_SIZE, new FilmQuery("", "", "", null, null, null, null, 0, 5000).size());
    }

    @Test
    void channelsAndWhatWasSeenRecently() {
        fill();
        assertEquals(List.of(new ChannelCount("ard", "Das Erste", 2), new ChannelCount("ard", "WDR", 1)),
                store.channels());
        assertEquals(3, store.count());
        assertEquals(Set.of("a", "c"), store.seenSince("ard", List.of("a", "c", "x"), T.minusSeconds(1)));
        assertEquals(Set.of(), store.seenSince("ard", List.of("a"), T));
        assertEquals(Set.of(), store.seenSince("zdf", List.of("a"), T.minusSeconds(1)));
        assertEquals(Set.of(), store.seenSince("ard", List.of(), T));
    }

    List<String> titles(String text) {
        return store.search(query(text)).films().stream().map(Film::title).toList();
    }

    static FilmQuery query(String text) {
        return new FilmQuery(text, "", "", null, null, null, null, 0, 50);
    }
}
