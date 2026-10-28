package eu.amadeos.tvapi.model;

/** A broadcaster and how many films of it are available. */
public record ChannelCount(String source, String channel, long films) {
}
