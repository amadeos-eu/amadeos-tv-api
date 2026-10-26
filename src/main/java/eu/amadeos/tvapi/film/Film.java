package eu.amadeos.tvapi.film;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A film of a broadcaster's media library. The video stays on the broadcaster's server; this is what is
 * known about it and where to get it.
 *
 * @param id               our id; null for a film the crawler just found
 * @param source           the media library it comes from ({@code ard}, {@code zdf}, ...)
 * @param sourceId         its id there; a version with audio description or sign language has its own
 * @param channel          the broadcaster, as the media library names it ("Das Erste", "WDR", ...)
 * @param topic            the series or show, empty for a single film
 * @param publishedAt      first broadcast or publication, may be null
 * @param duration         may be null
 * @param availableUntil   when the broadcaster takes it down, may be null
 * @param geo              where it may be shown ({@code DE}, {@code DE-AT-CH}), empty if everywhere
 * @param websiteUrl       the film's page at the broadcaster
 * @param videoSd          MP4 in normal quality, may be empty
 * @param videoHd          MP4 in the best quality, may be empty
 * @param videoHls         HLS playlist, may be empty
 */
public record Film(UUID id, String source, String sourceId, String channel, String topic, String title,
                   String description, Instant publishedAt, Duration duration, Instant availableUntil, String geo,
                   String websiteUrl, String subtitleUrl, String videoSd, String videoHd, String videoHls,
                   boolean audioDescription, boolean signLanguage) {

    public Film {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(title, "title");
        topic = text(topic);
        description = text(description);
        geo = text(geo);
        websiteUrl = text(websiteUrl);
        subtitleUrl = text(subtitleUrl);
        videoSd = text(videoSd);
        videoHd = text(videoHd);
        videoHls = text(videoHls);
    }

    /** Something to play. */
    public boolean hasVideo() {
        return !videoSd.isEmpty() || !videoHd.isEmpty() || !videoHls.isEmpty();
    }

    public Film withId(UUID newId) {
        return new Film(newId, source, sourceId, channel, topic, title, description, publishedAt, duration,
                availableUntil, geo, websiteUrl, subtitleUrl, videoSd, videoHd, videoHls, audioDescription,
                signLanguage);
    }

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }
}
