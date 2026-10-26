package eu.amadeos.tvapi.crawl.ard;

import eu.amadeos.tvapi.crawl.Http;
import eu.amadeos.tvapi.crawl.MediathekSource;
import eu.amadeos.tvapi.film.Film;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ARD Mediathek: Das Erste, the regional broadcasters, ONE, ARD-alpha, tagesschau24 and more.
 *
 * <p>What was published on a day comes from ARD's program service, the one behind the program pages and
 * TV's guide: every program item that has a video in the Mediathek links to it by its id.
 * <pre>
 * GET https://programm-api.ard.de/program/api/program?day=2026-10-25
 * { "channels": [ { "timeSlots": [ [ { "title": "Tatort", "links": { "target": { "urlId": "Y3JpZDov..." } } } ] ] } ] }
 * </pre>
 * The film itself is the Mediathek's item page, with the video in several qualities and versions:
 * <pre>
 * GET https://api.ardmediathek.de/page-gateway/pages/ard/item/Y3JpZDov...?embedded=true&amp;mcV6=true
 * { "widgets": [ { "title": "...", "synopsis": "...", "broadcastedOn": "...", "show": { "title": "Tatort" },
 *                  "publicationService": { "name": "Das Erste" },
 *                  "mediaCollection": { "embedded": { "meta": { "durationSeconds": 5400 }, "isGeoBlocked": false,
 *                      "streams": [ { "kind": "main", "media": [ { "mimeType": "video/mp4", "maxHResolutionPx": 720,
 *                          "url": "https://...mp4", "audios": [ { "kind": "standard", "languageCode": "deu" } ] } ] } ],
 *                      "subtitles": [ { "sources": [ { "kind": "webvtt", "url": "https://...vtt" } ] } ] } } } ] }
 * </pre>
 */
@Component
public class ArdSource implements MediathekSource {
    static final String DAY_URL = "https://programm-api.ard.de/program/api/program?day=%s";
    static final String ITEM_URL = "https://api.ardmediathek.de/page-gateway/pages/ard/item/%s?embedded=true&mcV6=true";
    static final String WEBSITE_URL = "https://www.ardmediathek.de/video/%s";
    /** From this height on a video counts as HD. */
    static final int HD_HEIGHT = 720;

    /** The versions of a film: the main one, with audio description, with sign language. */
    private enum Version {
        MAIN("main", "standard", ""), AUDIO_DESCRIPTION("main", "audio-description", "-ad"),
        SIGN_LANGUAGE("sign-language", "standard", "-dgs");

        final String streamKind;
        final String audioKind;
        final String suffix;

        Version(String streamKind, String audioKind, String suffix) {
            this.streamKind = streamKind;
            this.audioKind = audioKind;
            this.suffix = suffix;
        }
    }

    private final Http http;
    private final ObjectMapper json;

    public ArdSource(Http http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String id() {
        return "ard";
    }

    @Override
    public List<String> published(LocalDate day) throws IOException {
        JsonNode program = read(http.get(DAY_URL.formatted(day)));
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode channel : program.path("channels")) {
            for (JsonNode column : channel.path("timeSlots")) {
                for (JsonNode slot : column) {
                    String id = slot.path("links").path("target").path("urlId").asString("");
                    if (id.isEmpty()) id = slot.path("urlId").asString("");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public List<Film> films(String itemId) throws IOException {
        return parseItem(itemId, read(http.get(ITEM_URL.formatted(itemId))));
    }

    static List<Film> parseItem(String itemId, JsonNode page) {
        JsonNode item = page.path("widgets").path(0);
        JsonNode media = item.path("mediaCollection").path("embedded");
        String title = item.path("title").asString("").strip();
        if (title.isEmpty() || media.isMissingNode()) return List.of();
        String topic = item.path("show").path("title").asString("");
        String channel = item.path("publicationService").path("name").asString("ARD");
        long seconds = media.path("meta").path("durationSeconds").asLong(0);
        Duration duration = seconds > 0 ? Duration.ofSeconds(seconds) : null;
        String geo = media.path("isGeoBlocked").asBoolean(false) ? "DE" : "";
        String subtitles = subtitles(media);

        List<Film> films = new ArrayList<>();
        for (Version v : Version.values()) {
            Videos videos = videos(media, v);
            if (videos.isEmpty()) continue;
            String versionTitle = switch (v) {
                case MAIN -> title;
                case AUDIO_DESCRIPTION -> title + " (Audiodeskription)";
                case SIGN_LANGUAGE -> title + " (Gebärdensprache)";
            };
            films.add(new Film(null, "ard", itemId + v.suffix, channel, topic, versionTitle,
                    item.path("synopsis").asString(""), instant(item.path("broadcastedOn")), duration,
                    instant(item.path("availableTo")), geo, WEBSITE_URL.formatted(itemId), subtitles,
                    videos.sd, videos.hd, videos.hls, v == Version.AUDIO_DESCRIPTION, v == Version.SIGN_LANGUAGE));
        }
        return films;
    }

    private record Videos(String sd, String hd, String hls) {
        boolean isEmpty() {
            return sd.isEmpty() && hd.isEmpty() && hls.isEmpty();
        }
    }

    /**
     * The MP4 files and the HLS playlist of a version, in German if there is German, otherwise in the
     * first language there is (original versions).
     */
    private static Videos videos(JsonNode media, Version v) {
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode stream : media.path("streams")) {
            if (!v.streamKind.equals(stream.path("kind").asString(""))) continue;
            for (JsonNode m : stream.path("media")) {
                JsonNode audio = m.path("audios").path(0);
                if (v.audioKind.equals(audio.path("kind").asString("standard"))) candidates.add(m);
            }
        }
        boolean hasGerman = candidates.stream().anyMatch(ArdSource::isGerman);
        String sd = "", hd = "", hls = "";
        int sdHeight = 0, hdHeight = 0;
        for (JsonNode m : candidates) {
            if (hasGerman && !isGerman(m)) continue;
            String url = https(m.path("url").asString(""));
            if (url.isEmpty()) continue;
            String mime = m.path("mimeType").asString("");
            int height = m.path("maxHResolutionPx").asInt(0);
            if (mime.equals("application/vnd.apple.mpegurl")) {
                if (hls.isEmpty()) hls = url;
            } else if (mime.equals("video/mp4")) {
                if (height >= HD_HEIGHT && height > hdHeight) {
                    hd = url;
                    hdHeight = height;
                } else if (height < HD_HEIGHT && height > sdHeight) {
                    sd = url;
                    sdHeight = height;
                }
            }
        }
        return new Videos(sd, hd, hls);
    }

    private static boolean isGerman(JsonNode m) {
        return "deu".equals(m.path("audios").path(0).path("languageCode").asString(""));
    }

    /** WebVTT if there is one, otherwise the first subtitle file. */
    private static String subtitles(JsonNode media) {
        String first = "";
        for (JsonNode source : media.path("subtitles").path(0).path("sources")) {
            String url = https(source.path("url").asString(""));
            if (url.isEmpty()) continue;
            if (source.path("kind").asString("").toLowerCase().contains("vtt")) return url;
            if (first.isEmpty()) first = url;
        }
        return first;
    }

    /** Some addresses come without a scheme: "//...". */
    static String https(String url) {
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static Instant instant(JsonNode node) {
        String text = node.asString("");
        if (text.isEmpty()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode read(String body) throws IOException {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new IOException("ARD: not JSON", e);
        }
    }
}
