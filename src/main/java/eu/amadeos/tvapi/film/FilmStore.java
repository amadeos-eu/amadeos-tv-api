package eu.amadeos.tvapi.film;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The films in PostgreSQL, with the full-text search of its German configuration. */
@Repository
public class FilmStore {
    /** Whether {@link #save} added a film or changed one that was known. */
    public enum Saved { NEW, UPDATED }

    private static final RowMapper<Film> FILM = FilmStore::film;

    private final JdbcClient jdbc;

    public FilmStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the film, or updates the one with the same source and source id; either way it was seen now. */
    public Saved save(Film f, Instant now) {
        boolean inserted = jdbc.sql("""
                        insert into film (source, source_id, channel, topic, title, description, published_at,
                            duration_s, available_until, geo, website_url, subtitle_url, video_sd, video_hd, video_hls,
                            audio_description, sign_language, first_seen, last_seen)
                        values (:source, :sourceId, :channel, :topic, :title, :description, :publishedAt,
                            :duration, :availableUntil, :geo, :websiteUrl, :subtitleUrl, :videoSd, :videoHd, :videoHls,
                            :audioDescription, :signLanguage, :now, :now)
                        on conflict (source, source_id) do update set
                            channel = excluded.channel, topic = excluded.topic, title = excluded.title,
                            description = excluded.description, published_at = excluded.published_at,
                            duration_s = excluded.duration_s, available_until = excluded.available_until,
                            geo = excluded.geo, website_url = excluded.website_url,
                            subtitle_url = excluded.subtitle_url, video_sd = excluded.video_sd,
                            video_hd = excluded.video_hd, video_hls = excluded.video_hls,
                            audio_description = excluded.audio_description, sign_language = excluded.sign_language,
                            last_seen = excluded.last_seen, gone_at = null
                        returning (xmax = 0) as inserted
                        """)
                .param("source", f.source())
                .param("sourceId", f.sourceId())
                .param("channel", f.channel())
                .param("topic", f.topic())
                .param("title", f.title())
                .param("description", f.description())
                .param("publishedAt", time(f.publishedAt()))
                .param("duration", f.duration() == null ? null : (int) f.duration().toSeconds())
                .param("availableUntil", time(f.availableUntil()))
                .param("geo", f.geo())
                .param("websiteUrl", f.websiteUrl())
                .param("subtitleUrl", f.subtitleUrl())
                .param("videoSd", f.videoSd())
                .param("videoHd", f.videoHd())
                .param("videoHls", f.videoHls())
                .param("audioDescription", f.audioDescription())
                .param("signLanguage", f.signLanguage())
                .param("now", time(now))
                .query(Boolean.class).single();
        return inserted ? Saved.NEW : Saved.UPDATED;
    }

    /** Of these ids of a source, the ones seen after {@code since}: the crawler need not ask for them again. */
    public Set<String> seenSince(String source, Collection<String> sourceIds, Instant since) {
        if (sourceIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("""
                        select source_id from film
                        where source = :source and source_id in (:ids) and last_seen > :since
                        """)
                .param("source", source)
                .param("ids", List.copyOf(sourceIds))
                .param("since", time(since))
                .query(String.class).list());
    }

    public Optional<Film> get(UUID id) {
        return jdbc.sql("select * from film where id = :id and gone_at is null").param("id", id).query(FILM).optional();
    }

    /** The films that match, the best hits first when there are words, otherwise the newest first. */
    public FilmPage search(FilmQuery q) {
        StringBuilder where = new StringBuilder(" where gone_at is null");
        List<Object[]> params = new ArrayList<>();
        if (!q.text().isEmpty()) {
            where.append(" and search @@ websearch_to_tsquery('german', :text)");
            params.add(new Object[]{"text", q.text()});
        }
        if (!q.channel().isEmpty()) {
            where.append(" and lower(channel) = lower(:channel)");
            params.add(new Object[]{"channel", q.channel()});
        }
        if (!q.topic().isEmpty()) {
            where.append(" and lower(topic) = lower(:topic)");
            params.add(new Object[]{"topic", q.topic()});
        }
        if (q.from() != null) {
            where.append(" and published_at >= :from");
            params.add(new Object[]{"from", time(q.from())});
        }
        if (q.to() != null) {
            where.append(" and published_at < :to");
            params.add(new Object[]{"to", time(q.to())});
        }
        if (q.minDuration() != null) {
            where.append(" and duration_s >= :min");
            params.add(new Object[]{"min", (int) q.minDuration().toSeconds()});
        }
        if (q.maxDuration() != null) {
            where.append(" and duration_s <= :max");
            params.add(new Object[]{"max", (int) q.maxDuration().toSeconds()});
        }
        String order = q.text().isEmpty() ? " order by published_at desc nulls last, id"
                : " order by ts_rank(search, websearch_to_tsquery('german', :text)) desc, published_at desc nulls last, id";

        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from film" + where);
        JdbcClient.StatementSpec page = jdbc.sql("select * from film" + where + order + " limit :limit offset :offset")
                .param("limit", q.size())
                .param("offset", (long) q.page() * q.size());
        for (Object[] p : params) {
            count = count.param((String) p[0], p[1]);
            page = page.param((String) p[0], p[1]);
        }
        return new FilmPage(count.query(Long.class).single(), q.page(), q.size(), page.query(FILM).list());
    }

    public List<ChannelCount> channels() {
        return jdbc.sql("""
                        select source, channel, count(*) as films from film where gone_at is null
                        group by source, channel order by source, channel
                        """)
                .query((rs, n) -> new ChannelCount(rs.getString("source"), rs.getString("channel"), rs.getLong("films")))
                .list();
    }

    public long count() {
        return jdbc.sql("select count(*) from film where gone_at is null").query(Long.class).single();
    }

    private static Film film(ResultSet rs, int row) throws SQLException {
        int seconds = rs.getInt("duration_s");
        Duration duration = rs.wasNull() ? null : Duration.ofSeconds(seconds);
        return new Film(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("source_id"),
                rs.getString("channel"), rs.getString("topic"), rs.getString("title"), rs.getString("description"),
                instant(rs, "published_at"), duration, instant(rs, "available_until"), rs.getString("geo"),
                rs.getString("website_url"), rs.getString("subtitle_url"), rs.getString("video_sd"),
                rs.getString("video_hd"), rs.getString("video_hls"), rs.getBoolean("audio_description"),
                rs.getBoolean("sign_language"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime time(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }
}
