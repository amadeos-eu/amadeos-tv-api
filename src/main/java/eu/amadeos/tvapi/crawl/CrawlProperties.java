package eu.amadeos.tvapi.crawl;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Settings of the crawler ({@code crawl.*} in application.yml).
 *
 * @param sources      the ids of the sources to ask
 * @param days         how many days back from today, today included
 * @param pause        the pause between two requests to the same host
 * @param refreshAfter a film seen this recently is not asked for again
 * @param timeout      for one request
 */
@ConfigurationProperties("crawl")
public record CrawlProperties(List<String> sources, int days, Duration pause, Duration refreshAfter,
                              Duration timeout) {
    public CrawlProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        days = Math.max(1, days);
        pause = pause == null ? Duration.ofMillis(300) : pause;
        refreshAfter = refreshAfter == null ? Duration.ofHours(24) : refreshAfter;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }
}
