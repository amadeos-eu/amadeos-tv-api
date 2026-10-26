package eu.amadeos.tvapi.crawl;

import eu.amadeos.tvapi.film.Film;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * One broadcaster's media library. The crawler asks what was published on a day, and then, for the ids
 * it does not know yet, the films themselves: a day costs one request, a film one more, and a film that
 * is known costs nothing.
 */
public interface MediathekSource {
    /** Short and stable: {@code ard}, {@code zdf}. */
    String id();

    /** The ids of the items published on that day, in the source's own terms. */
    List<String> published(LocalDate day) throws IOException;

    /**
     * The films of an item: usually one, more when there are versions with audio description or sign
     * language; none when it has no video (yet). Their {@link Film#sourceId()} starts with {@code itemId}.
     */
    List<Film> films(String itemId) throws IOException;
}
