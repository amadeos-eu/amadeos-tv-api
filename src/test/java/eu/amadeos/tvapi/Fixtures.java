package eu.amadeos.tvapi;

import eu.amadeos.tvapi.model.Film;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Recorded-style answers (src/test/resources) and films to store. */
public final class Fixtures {
    private Fixtures() {
    }

    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalArgumentException("No fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Film film(String id, String channel, String topic, String title, String description,
                            Instant published, int minutes) {
        return new Film(null, "ard", id, channel, topic, title, description, published, Duration.ofMinutes(minutes),
                null, "", "https://www.ardmediathek.de/video/" + id, "", "https://example.org/" + id + ".mp4", "",
                "", false, false);
    }
}
