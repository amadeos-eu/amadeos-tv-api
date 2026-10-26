package eu.amadeos.tvapi.api;

import eu.amadeos.tvapi.film.Film;

import java.time.Instant;
import java.util.UUID;

/** A film as the API shows it: the duration in seconds, times in ISO 8601 (UTC), no internal fields. */
public record FilmJson(UUID id, String source, String channel, String topic, String title, String description,
                       Instant publishedAt, Long durationSeconds, Instant availableUntil, String geo, String websiteUrl,
                       String subtitleUrl, String videoSd, String videoHd, String videoHls, boolean audioDescription,
                       boolean signLanguage) {

    static FilmJson of(Film f) {
        return new FilmJson(f.id(), f.source(), f.channel(), f.topic(), f.title(), f.description(), f.publishedAt(),
                f.duration() == null ? null : f.duration().toSeconds(), f.availableUntil(), f.geo(), f.websiteUrl(),
                f.subtitleUrl(), f.videoSd(), f.videoHd(), f.videoHls(), f.audioDescription(), f.signLanguage());
    }
}
