package eu.amadeos.tvapi.crawl.zdf;

import eu.amadeos.tvapi.crawl.Http;
import eu.amadeos.tvapi.crawl.HttpStatusException;
import eu.amadeos.tvapi.crawl.MediathekSource;
import eu.amadeos.tvapi.model.Film;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ZDF Mediathek: ZDF, ZDFneo, ZDFinfo and ZDFtivi. 3sat, KiKA, phoenix and ARTE show up in ZDF's search
 * too, but come from their own libraries (or from ARD's), so they are left out here.
 *
 * <p>ZDF's content API wants a token in the {@code Api-Auth} header, the one zdf.de hands to its own
 * pages. It is read from the start page at the beginning of a run, and once more when the API stops
 * taking it. The day comes from the search:
 * <pre>
 * GET https://api.zdf.de/search/documents?hasVideo=true&amp;q=*&amp;types=page-video&amp;sortBy=date&amp;from=...&amp;to=...
 * { "http://zdf.de/rels/search/results": [ { "http://zdf.de/rels/target": {
 *       "canonical": "/content/documents/das-duo-echte-kerle-102.json",
 *       "mainVideoContent": { "http://zdf.de/rels/target": { ... } } } } ],
 *   "next": "/search/documents?...&amp;page=2" }
 * </pre>
 * A film is its document, and its videos are in the "ptmd" answer the document points to:
 * <pre>
 * GET https://api.zdf.de/content/documents/das-duo-echte-kerle-102.json
 * { "title": "Echte Kerle", "leadParagraph": "...", "tvService": "ZDF", "editorialDate": "...",
 *   "http://zdf.de/rels/brand": { "title": "Das Duo" }, "http://zdf.de/rels/sharing-url": "https://www.zdf.de/...html",
 *   "programmeItem": [ { "http://zdf.de/rels/target": { "http://zdf.de/rels/cmdm/broadcasts": [ { "airtimeBegin": "..." } ] } } ],
 *   "mainVideoContent": { "http://zdf.de/rels/target": { "duration": 5255, "visibleTo": "...",
 *       "streams": { "default": { "http://zdf.de/rels/streams/ptmd-template": "/tmd/2/{playerId}/vod/ptmd/mediathek/..." },
 *                    "dgs": { ... } } } } }
 * GET https://api.zdf.de/tmd/2/android_native_5/vod/ptmd/mediathek/...
 * { "attributes": { "geoLocation": { "value": "de" } }, "captions": [ { "format": "webvtt", "uri": "..." } ],
 *   "priorityList": [ { "formitaeten": [ { "mimeType": "video/mp4", "qualities": [ { "quality": "hd",
 *       "highestVerticalResolution": 720, "audio": { "tracks": [ { "class": "main", "language": "deu", "uri": "..." } ] } } ] } ] } ] }
 * </pre>
 */
@Component
public class ZdfSource implements MediathekSource {
    private static final Logger LOG = LoggerFactory.getLogger(ZdfSource.class);
    static final String API = "https://api.zdf.de";
    static final String START_PAGE = "https://www.zdf.de/";
    static final String DAY_URL = API + "/search/documents?hasVideo=true&q=*&types=page-video&sortOrder=desc"
            + "&from=%sT00:00:00.000%s&to=%sT23:59:59.999%s&sortBy=date&page=1";
    static final String DOCUMENT_URL = API + "/content/documents/%s.json";
    static final String PLAYER_ID = "android_native_5";
    /** A day has a few hundred videos at most; this only stops a search that never ends. */
    static final int MAX_DAY_PAGES = 40;
    static final ZoneId BROADCAST_ZONE = ZoneId.of("Europe/Berlin");
    /** ZDF's own channels, by the name the documents give them. */
    static final Map<String, String> CHANNELS = Map.of("ZDF", "ZDF", "ZDFneo", "ZDFneo", "ZDFinfo", "ZDFinfo",
            "ZDFtivi", "ZDFtivi");
    /** {@code apiToken: '...'} in a script, or {@code "apiToken":"..."} in escaped or plain JSON. */
    static final Pattern TOKEN = Pattern.compile("apiToken\\\\?[\"']?\\s*[:=]\\s*\\\\?[\"']([0-9A-Za-z_\\-]{20,})");
    private static final String TARGET = "http://zdf.de/rels/target";
    private static final String PTMD = "http://zdf.de/rels/streams/ptmd-template";

    private final Http http;
    private final ObjectMapper json;
    private final ZdfProperties properties;
    private volatile Tokens tokens;

    /** The page's tokens: one for the search, one for the documents and videos; often the same. */
    record Tokens(String search, String video) {
    }

    public ZdfSource(Http http, ObjectMapper json, ZdfProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "zdf";
    }

    @Override
    public List<String> published(LocalDate day) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        String url = dayUrl(day);
        for (int page = 0; page < MAX_DAY_PAGES && url != null; page++) {
            JsonNode answer = read(api(url, true));
            int before = ids.size();
            for (JsonNode result : answer.path("http://zdf.de/rels/search/results")) {
                String id = videoPageId(result.path(TARGET));
                if (!id.isEmpty()) ids.add(id);
            }
            String next = answer.path("next").asString("");
            // A page with nothing new ends it too: ZDF's last page sometimes points to itself.
            url = next.isEmpty() || ids.size() == before ? null : nextUrl(next);
        }
        return List.copyOf(ids);
    }

    @Override
    public List<Film> films(String itemId) throws IOException {
        JsonNode document = read(api(DOCUMENT_URL.formatted(itemId), false));
        Page page = parseDocument(itemId, document);
        if (page == null) return List.of();
        Ptmd main = Ptmd.parse(read(api(ptmdUrl(page.mainPtmd), false)));
        Ptmd dgs = page.dgsPtmd.isEmpty() ? null : Ptmd.parse(read(api(ptmdUrl(page.dgsPtmd), false)));
        return films(page, main, dgs);
    }

    /** The day search, from midnight to midnight in Berlin, which is +01:00 or +02:00. */
    static String dayUrl(LocalDate day) {
        String from = offset(day.atStartOfDay(BROADCAST_ZONE).getOffset());
        String to = offset(day.plusDays(1).atStartOfDay(BROADCAST_ZONE).minusNanos(1).getOffset());
        return DAY_URL.formatted(day, from, day, to);
    }

    private static String offset(ZoneOffset o) {
        return o.getId().replace("+", "%2B");
    }

    /**
     * The next search page. ZDF writes the types there as numbers, and that search finds nothing, so they are
     * put back as names.
     */
    static String nextUrl(String next) {
        String url = next.startsWith("/") ? API + next : next;
        return url.replaceFirst("([?&])types=\\d+(&|$)", "$1types=page-video$2");
    }

    /** The document id of a search result that is a page with a video, or "". */
    static String videoPageId(JsonNode target) {
        if (!target.has("mainVideoContent") && target.has("http://zdf.de/rels/content/video-page-teaser")) {
            target = target.path("http://zdf.de/rels/content/video-page-teaser");
        }
        if (target.path("mainVideoContent").path(TARGET).isMissingNode()) return "";
        return documentId(target.path("canonical").asString(""));
    }

    /** "/content/documents/das-duo-echte-kerle-102.json" gives "das-duo-echte-kerle-102". */
    static String documentId(String canonical) {
        int slash = canonical.lastIndexOf('/');
        String name = canonical.substring(slash + 1);
        int query = name.indexOf('?');
        if (query >= 0) name = name.substring(0, query);
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : "";
    }

    static String ptmdUrl(String template) {
        String url = template.replace("{playerId}", PLAYER_ID);
        return url.startsWith("/") ? API + url : url;
    }

    /** What the document says about a film, before its videos are known. */
    record Page(String id, String channel, String topic, String title, String description, Instant publishedAt,
                Duration duration, Instant availableUntil, String websiteUrl, String mainPtmd, String dgsPtmd) {
    }

    /** The film's document, or null when it is not one of ZDF's channels or has no video. */
    static Page parseDocument(String id, JsonNode document) {
        String service = document.path("tvService").asString("ZDF");
        String channel = CHANNELS.get(service.isEmpty() ? "ZDF" : service);
        if (channel == null) return null;
        JsonNode video = document.path("mainVideoContent").path(TARGET);
        JsonNode programme = document.path("programmeItem").path(0).path(TARGET);
        String main = video.path("streams").path("default").path(PTMD).asString("");
        if (main.isEmpty()) main = video.path(PTMD).asString("");
        if (main.isEmpty()) return null;
        String title = title(document);
        if (title.isEmpty()) title = title(programme);
        if (title.isEmpty()) return null;
        title = (title + " " + episode(programme)).strip();

        String topic = document.path("http://zdf.de/rels/brand").path("title").asString("");
        if (topic.isEmpty()) topic = document.path("http://zdf.de/rels/category").path("title").asString("");
        String description = document.path("leadParagraph").asString("");
        if (description.isEmpty()) description = document.path("teasertext").asString("");
        Instant published = instant(programme.path("http://zdf.de/rels/cmdm/broadcasts").path(0).path("airtimeBegin"));
        if (published == null) published = instant(document.path("editorialDate"));
        long seconds = video.path("duration").asLong(0);
        return new Page(id, channel, topic, title, description.strip(), published,
                seconds > 0 ? Duration.ofSeconds(seconds) : null, instant(video.path("visibleTo")),
                document.path("http://zdf.de/rels/sharing-url").asString(""), main,
                video.path("streams").path("dgs").path(PTMD).asString(""));
    }

    /** "Title - Subtitle", without the Creative Commons notes some clips carry in the title. */
    private static String title(JsonNode node) {
        String title = node.path("title").asString("").strip();
        if (title.isEmpty()) return "";
        String subtitle = node.path("subtitle").asString("").strip();
        if (!subtitle.isEmpty() && !subtitle.equals(title)) title += " - " + subtitle;
        return title.replaceAll("\\s*\\(CC.*\\) - .* Creative Commons.*", "");
    }

    /** "(S02/E05)", "(E05)", or "". */
    private static String episode(JsonNode programme) {
        int season = programme.path("http://zdf.de/rels/cmdm/season").path("seasonNumber").asInt(0);
        int episode = programme.path("episodeNumber").asInt(0);
        if (season == 0 && episode == 0) return "";
        String s = season > 0 ? "S%02d".formatted(season) : "";
        String e = episode > 0 ? "E%02d".formatted(episode) : "";
        return "(" + s + (s.isEmpty() || e.isEmpty() ? "" : "/") + e + ")";
    }

    /** The main version, the one with audio description, and the one with sign language, if there are. */
    static List<Film> films(Page page, Ptmd main, Ptmd dgs) {
        List<Film> films = new ArrayList<>();
        Ptmd.Videos normal = main.videos("main");
        if (!normal.isEmpty()) films.add(film(page, "", "", main, normal, false, false));
        Ptmd.Videos ad = main.videos("ad");
        if (!ad.isEmpty()) films.add(film(page, "-ad", " (Audiodeskription)", main, ad, true, false));
        if (dgs != null) {
            Ptmd.Videos sign = dgs.videos("main");
            if (!sign.isEmpty()) films.add(film(page, "-dgs", " (Gebärdensprache)", dgs, sign, false, true));
        }
        return films;
    }

    private static Film film(Page page, String suffix, String titleSuffix, Ptmd ptmd, Ptmd.Videos videos,
                             boolean audioDescription, boolean signLanguage) {
        Duration duration = page.duration != null ? page.duration : ptmd.duration();
        return new Film(null, "zdf", page.id + suffix, page.channel, page.topic, page.title + titleSuffix,
                page.description, page.publishedAt, duration, page.availableUntil, ptmd.geo(), page.websiteUrl,
                ptmd.subtitles(), videos.sd(), videos.hd(), videos.hls(), audioDescription, signLanguage);
    }

    /** A GET to ZDF's API with the token; when the token is not taken any more, a new one, once. */
    private String api(String url, boolean search) throws IOException {
        Tokens t = tokens();
        try {
            return http.get(url, headers(search ? t.search() : t.video()));
        } catch (HttpStatusException e) {
            if (e.status() != 401 && e.status() != 403) throw e;
            LOG.info("ZDF did not take the token ({}), reading it again", e.status());
            tokens = null;
            t = tokens();
            return http.get(url, headers(search ? t.search() : t.video()));
        }
    }

    private static Map<String, String> headers(String token) {
        return Map.of("Api-Auth", "Bearer " + token, "Origin", "https://www.zdf.de", "Referer", START_PAGE);
    }

    private Tokens tokens() throws IOException {
        Tokens t = tokens;
        if (t == null) {
            if (!properties.searchToken().isEmpty() && !properties.videoToken().isEmpty()) {
                t = new Tokens(properties.searchToken(), properties.videoToken());
            } else {
                t = tokens(http.get(START_PAGE, Map.of("Accept", "text/html")));
            }
            tokens = t;
        }
        return t;
    }

    /**
     * The tokens in zdf.de's page. The page has had the search token first and the video token further down;
     * when there is only one, it is used for both.
     */
    static Tokens tokens(String page) throws IOException {
        Matcher m = TOKEN.matcher(page);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (!found.contains(m.group(1))) found.add(m.group(1));
        }
        if (found.isEmpty()) throw new IOException("ZDF: no apiToken on " + START_PAGE);
        return new Tokens(found.getFirst(), found.getLast());
    }

    static Instant instant(JsonNode node) {
        String text = node.asString("");
        if (text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private JsonNode read(String body) throws IOException {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new IOException("ZDF: not JSON", e);
        }
    }
}
