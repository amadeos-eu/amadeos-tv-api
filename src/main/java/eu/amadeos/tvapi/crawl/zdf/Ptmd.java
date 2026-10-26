package eu.amadeos.tvapi.crawl.zdf;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ZDF's answer about the videos of a film ("ptmd"): the formats, each in several qualities, each quality with
 * one file per audio track. The track's class tells the versions apart: {@code main} is the normal one,
 * {@code ad} the one with audio description.
 */
record Ptmd(List<Track> tracks, String subtitles, String geo, Duration duration) {
    static final String MP4 = "video/mp4";
    static final String HLS = "application/x-mpegurl";
    /** From this height on a video counts as HD; above the last one it is left out, files get too big. */
    static final int HD_HEIGHT = 720;
    static final int MAX_HEIGHT = 1080;
    /** Heights of ZDF's quality names, for answers without {@code highestVerticalResolution}. */
    static final Map<String, Integer> HEIGHTS = Map.of("low", 270, "med", 360, "medium", 360, "high", 480,
            "veryhigh", 540, "hd", 720, "fhd", 1080, "uhd", 2160);
    /** ZDF's geo names, in the notation the API gives out. */
    static final Map<String, String> GEO = Map.of("de", "DE", "at", "AT", "ch", "CH", "eu", "EU",
            "dach", "DE-AT-CH", "ebu", "DE-AT-CH-EU", "sat", "DE-AT-CH-EU");

    /** One file: its format, height, version class and language. */
    record Track(String mimeType, int height, String kind, String language, String url) {
        boolean isGerman() {
            return language.equals("deu");
        }
    }

    record Videos(String sd, String hd, String hls) {
        boolean isEmpty() {
            return sd.isEmpty() && hd.isEmpty() && hls.isEmpty();
        }
    }

    static Ptmd parse(JsonNode answer) {
        List<Track> tracks = new ArrayList<>();
        for (JsonNode priority : answer.path("priorityList")) {
            for (JsonNode format : priority.path("formitaeten")) {
                String mime = format.path("mimeType").asString("").toLowerCase(Locale.ROOT);
                if (!mime.equals(MP4) && !mime.equals(HLS)) continue;
                for (JsonNode quality : format.path("qualities")) {
                    int height = quality.path("highestVerticalResolution").asInt(0);
                    if (height == 0) height = HEIGHTS.getOrDefault(quality.path("quality").asString(""), 0);
                    for (JsonNode track : quality.path("audio").path("tracks")) {
                        String url = track.path("uri").asString("");
                        if (!url.isEmpty()) {
                            tracks.add(new Track(mime, height, track.path("class").asString("main"),
                                    track.path("language").asString(""), url));
                        }
                    }
                }
            }
        }
        JsonNode attributes = answer.path("attributes");
        String geo = attributes.path("geoLocation").path("value").asString("").toLowerCase(Locale.ROOT);
        long millis = attributes.path("duration").path("value").asLong(0);
        return new Ptmd(tracks, subtitles(answer.path("captions")),
                geo.isEmpty() || geo.equals("none") ? "" : GEO.getOrDefault(geo, geo.toUpperCase(Locale.ROOT)),
                millis > 0 ? Duration.ofMillis(millis) : null);
    }

    /** German WebVTT if there is, otherwise any German subtitles, otherwise the first ones. */
    private static String subtitles(JsonNode captions) {
        String german = "", first = "";
        for (JsonNode caption : captions) {
            String url = caption.path("uri").asString("");
            if (url.isEmpty()) continue;
            boolean isGerman = caption.path("language").asString("deu").equals("deu");
            boolean vtt = caption.path("format").asString("").equalsIgnoreCase("webvtt") || url.endsWith(".vtt");
            if (isGerman && vtt) return url;
            if (isGerman && german.isEmpty()) german = url;
            if (first.isEmpty()) first = url;
        }
        return german.isEmpty() ? first : german;
    }

    /** The files of a version ({@code main}, {@code ad}), in German if there is German. */
    Videos videos(String kind) {
        List<Track> mine = tracks.stream().filter(t -> t.kind.equals(kind)).toList();
        boolean hasGerman = mine.stream().anyMatch(Track::isGerman);
        String sd = "", hd = "", hls = "";
        int sdHeight = -1, hdHeight = -1;
        for (Track t : mine) {
            if (hasGerman && !t.isGerman()) continue;
            if (t.mimeType.equals(HLS)) {
                if (hls.isEmpty()) hls = t.url;
            } else if (t.height >= HD_HEIGHT && t.height <= MAX_HEIGHT) {
                if (t.height > hdHeight) {
                    hd = t.url;
                    hdHeight = t.height;
                }
            } else if (t.height < HD_HEIGHT && t.height > sdHeight) {
                sd = t.url;
                sdHeight = t.height;
            }
        }
        return new Videos(sd, hd, hls);
    }
}
