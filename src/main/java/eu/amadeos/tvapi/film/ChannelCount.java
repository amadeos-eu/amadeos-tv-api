package eu.amadeos.tvapi.film;

/** A broadcaster and how many films of it are available. */
public record ChannelCount(String source, String channel, long films) {
}
