package eu.amadeos.tvapi.repository;

import eu.amadeos.tvapi.model.ChannelCount;
import eu.amadeos.tvapi.model.Film;
import eu.amadeos.tvapi.model.FilmPage;
import eu.amadeos.tvapi.model.FilmQuery;
import eu.amadeos.tvapi.model.Saved;
import eu.amadeos.tvapi.repository.jooq.tables.records.FilmRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static eu.amadeos.tvapi.repository.jooq.Tables.FILM;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.val;

/** The films in PostgreSQL, with the full-text search of its German configuration. */
@Repository
public class FilmRepository {
    /** What saving a known film again changes: everything the crawler found, but not when it was first seen. */
    private static final List<TableField<FilmRecord, ?>> UPDATED = List.of(FILM.CHANNEL, FILM.TOPIC, FILM.TITLE,
            FILM.DESCRIPTION, FILM.PUBLISHED_AT, FILM.DURATION_S, FILM.AVAILABLE_UNTIL, FILM.GEO, FILM.WEBSITE_URL,
            FILM.SUBTITLE_URL, FILM.VIDEO_SD, FILM.VIDEO_HD, FILM.VIDEO_HLS, FILM.AUDIO_DESCRIPTION,
            FILM.SIGN_LANGUAGE, FILM.LAST_SEEN);
    /** PostgreSQL's way to tell an insert from an update in {@code on conflict ... returning}. */
    private static final Field<Boolean> INSERTED = field("xmax = 0", Boolean.class);

    private final DSLContext dsl;

    public FilmRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Adds the film, or updates the one with the same source and source id; either way it was seen now. */
    public Saved save(Film f, Instant now) {
        Map<Field<?>, Object> update = new LinkedHashMap<>();
        for (TableField<FilmRecord, ?> column : UPDATED) update.put(column, excluded(column));
        update.put(FILM.GONE_AT, null);
        boolean inserted = dsl.insertInto(FILM)
                .set(FILM.SOURCE, f.source())
                .set(FILM.SOURCE_ID, f.sourceId())
                .set(FILM.CHANNEL, f.channel())
                .set(FILM.TOPIC, f.topic())
                .set(FILM.TITLE, f.title())
                .set(FILM.DESCRIPTION, f.description())
                .set(FILM.PUBLISHED_AT, f.publishedAt())
                .set(FILM.DURATION_S, f.duration() == null ? null : (int) f.duration().toSeconds())
                .set(FILM.AVAILABLE_UNTIL, f.availableUntil())
                .set(FILM.GEO, f.geo())
                .set(FILM.WEBSITE_URL, f.websiteUrl())
                .set(FILM.SUBTITLE_URL, f.subtitleUrl())
                .set(FILM.VIDEO_SD, f.videoSd())
                .set(FILM.VIDEO_HD, f.videoHd())
                .set(FILM.VIDEO_HLS, f.videoHls())
                .set(FILM.AUDIO_DESCRIPTION, f.audioDescription())
                .set(FILM.SIGN_LANGUAGE, f.signLanguage())
                .set(FILM.FIRST_SEEN, now)
                .set(FILM.LAST_SEEN, now)
                .onConflict(FILM.SOURCE, FILM.SOURCE_ID)
                .doUpdate().set(update)
                .returningResult(INSERTED)
                .fetchSingle().value1();
        return inserted ? Saved.NEW : Saved.UPDATED;
    }

    /** Of these ids of a source, the ones seen after {@code since}: the crawler need not ask for them again. */
    public Set<String> seenSince(String source, Collection<String> sourceIds, Instant since) {
        if (sourceIds.isEmpty()) return Set.of();
        return dsl.select(FILM.SOURCE_ID).from(FILM)
                .where(FILM.SOURCE.eq(source), FILM.SOURCE_ID.in(sourceIds), FILM.LAST_SEEN.gt(since))
                .fetchSet(FILM.SOURCE_ID);
    }

    public Optional<Film> get(UUID id) {
        return dsl.selectFrom(FILM).where(FILM.ID.eq(id), FILM.GONE_AT.isNull()).fetchOptional(FilmRepository::film);
    }

    /** The films that match, the best hits first when there are words, otherwise the newest first. */
    public FilmPage search(FilmQuery q) {
        List<Condition> where = new ArrayList<>();
        where.add(FILM.GONE_AT.isNull());
        List<SortField<?>> order = new ArrayList<>();
        if (!q.text().isEmpty()) {
            Field<Object> words = field("websearch_to_tsquery('german', {0})", val(q.text()));
            where.add(condition("{0} @@ {1}", FILM.SEARCH, words));
            order.add(field("ts_rank({0}, {1})", Double.class, FILM.SEARCH, words).desc());
        }
        if (!q.channel().isEmpty()) where.add(FILM.CHANNEL.equalIgnoreCase(q.channel()));
        if (!q.topic().isEmpty()) where.add(FILM.TOPIC.equalIgnoreCase(q.topic()));
        if (q.from() != null) where.add(FILM.PUBLISHED_AT.ge(q.from()));
        if (q.to() != null) where.add(FILM.PUBLISHED_AT.lt(q.to()));
        if (q.minDuration() != null) where.add(FILM.DURATION_S.ge((int) q.minDuration().toSeconds()));
        if (q.maxDuration() != null) where.add(FILM.DURATION_S.le((int) q.maxDuration().toSeconds()));
        order.add(FILM.PUBLISHED_AT.desc().nullsLast());
        order.add(FILM.ID.asc());

        List<Film> films = dsl.selectFrom(FILM).where(where).orderBy(order)
                .limit(q.size()).offset((long) q.page() * q.size())
                .fetch(FilmRepository::film);
        return new FilmPage(dsl.fetchCount(FILM, where), q.page(), q.size(), films);
    }

    public List<ChannelCount> channels() {
        return dsl.select(FILM.SOURCE, FILM.CHANNEL, DSL.count())
                .from(FILM).where(FILM.GONE_AT.isNull())
                .groupBy(FILM.SOURCE, FILM.CHANNEL).orderBy(FILM.SOURCE, FILM.CHANNEL)
                .fetch(r -> new ChannelCount(r.value1(), r.value2(), r.value3()));
    }

    public long count() {
        return dsl.fetchCount(FILM, FILM.GONE_AT.isNull());
    }

    private static Film film(FilmRecord r) {
        Duration duration = r.getDurationS() == null ? null : Duration.ofSeconds(r.getDurationS());
        return new Film(r.getId(), r.getSource(), r.getSourceId(), r.getChannel(), r.getTopic(), r.getTitle(),
                r.getDescription(), r.getPublishedAt(), duration, r.getAvailableUntil(), r.getGeo(),
                r.getWebsiteUrl(), r.getSubtitleUrl(), r.getVideoSd(), r.getVideoHd(), r.getVideoHls(),
                r.getAudioDescription(), r.getSignLanguage());
    }
}
