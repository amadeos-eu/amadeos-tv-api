package eu.amadeos.tvapi.repository;

import eu.amadeos.tvapi.model.CrawlRun;
import eu.amadeos.tvapi.repository.jooq.tables.records.CrawlRunRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static eu.amadeos.tvapi.repository.jooq.Tables.CRAWL_RUN;

/** What each run of the crawler did, per source: for /status, and to see when a source broke. */
@Repository
public class CrawlRunRepository {
    private final DSLContext dsl;

    public CrawlRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long start(String source, Instant now) {
        return dsl.insertInto(CRAWL_RUN)
                .set(CRAWL_RUN.SOURCE, source)
                .set(CRAWL_RUN.STARTED, now)
                .returningResult(CRAWL_RUN.ID)
                .fetchSingle().value1();
    }

    public void finish(long id, Instant now, int filmsNew, int filmsUpdated, String error) {
        dsl.update(CRAWL_RUN)
                .set(CRAWL_RUN.FINISHED, now)
                .set(CRAWL_RUN.FILMS_NEW, filmsNew)
                .set(CRAWL_RUN.FILMS_UPDATED, filmsUpdated)
                .set(CRAWL_RUN.ERROR, error)
                .where(CRAWL_RUN.ID.eq(id))
                .execute();
    }

    /** The latest run of each source. */
    public List<CrawlRun> latest() {
        return dsl.select(CRAWL_RUN.fields())
                .distinctOn(CRAWL_RUN.SOURCE)
                .from(CRAWL_RUN)
                .orderBy(CRAWL_RUN.SOURCE, CRAWL_RUN.STARTED.desc())
                .fetchInto(CRAWL_RUN)
                .map(CrawlRunRepository::run);
    }

    private static CrawlRun run(CrawlRunRecord r) {
        return new CrawlRun(r.getSource(), r.getStarted(), r.getFinished(), r.getFilmsNew(), r.getFilmsUpdated(),
                r.getError());
    }
}
