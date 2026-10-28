package eu.amadeos.tvapi.model;

import java.time.Instant;

/** A finished or running run of the crawler for one source; {@code error} is null when it went well. */
public record CrawlRun(String source, Instant started, Instant finished, int filmsNew, int filmsUpdated,
                       String error) {
}
