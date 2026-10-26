package eu.amadeos.tvapi.crawl;

import java.io.IOException;

/** What the sources ask of the network; the tests answer with recorded pages. */
@FunctionalInterface
public interface Http {
    /** The body of a GET, as text. */
    String get(String url) throws IOException;
}
