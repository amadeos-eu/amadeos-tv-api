package eu.amadeos.tvapi.crawl;

import java.io.IOException;
import java.util.Map;

/** What the sources ask of the network; the tests answer with recorded pages. */
@FunctionalInterface
public interface Http {
    /**
     * The body of a GET, as text. {@code headers} come on top of the usual ones, or replace them.
     *
     * @throws HttpStatusException when the server answers with something other than 200
     */
    String get(String url, Map<String, String> headers) throws IOException;

    /** The body of a GET with the usual headers. */
    default String get(String url) throws IOException {
        return get(url, Map.of());
    }
}
