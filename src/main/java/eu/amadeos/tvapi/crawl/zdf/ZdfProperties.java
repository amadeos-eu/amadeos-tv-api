package eu.amadeos.tvapi.crawl.zdf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the ZDF source ({@code crawl.zdf.*}). Normally both stay empty and the tokens are read from
 * zdf.de at the start of a run; when ZDF changes its page so that they cannot be found any more, they can be
 * set here by hand until the source is fixed.
 *
 * @param searchToken the token for the day search
 * @param videoToken  the token for the films and their videos
 */
@ConfigurationProperties("crawl.zdf")
public record ZdfProperties(String searchToken, String videoToken) {
    public ZdfProperties {
        searchToken = searchToken == null ? "" : searchToken.strip();
        videoToken = videoToken == null ? "" : videoToken.strip();
    }
}
