package eu.amadeos.tvapi.film;

import java.time.Duration;
import java.time.Instant;

/**
 * A search in the films; every part is optional.
 *
 * @param text  words as people type them: {@code tatort -münster "sonntag abend"}
 * @param page  from 0
 */
public record FilmQuery(String text, String channel, String topic, Instant from, Instant to, Duration minDuration,
                        Duration maxDuration, int page, int size) {
    public static final int MAX_SIZE = 100;

    public FilmQuery {
        text = text == null ? "" : text.strip();
        channel = channel == null ? "" : channel.strip();
        topic = topic == null ? "" : topic.strip();
        page = Math.max(0, page);
        size = size <= 0 ? 50 : Math.min(size, MAX_SIZE);
    }

    public static FilmQuery all() {
        return new FilmQuery("", "", "", null, null, null, null, 0, 50);
    }
}
