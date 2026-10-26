package eu.amadeos.tvapi.crawl;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** What each run of the crawler did, per source: for /status, and to see when a source broke. */
@Repository
public class CrawlRuns {
    /** A finished or running run; {@code error} is null when it went well. */
    public record Run(String source, Instant started, Instant finished, int filmsNew, int filmsUpdated, String error) {
    }

    private final JdbcClient jdbc;

    public CrawlRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String source, Instant now) {
        return jdbc.sql("insert into crawl_run (source, started) values (:source, :now) returning id")
                .param("source", source).param("now", now.atOffset(ZoneOffset.UTC)).query(Long.class).single();
    }

    public void finish(long id, Instant now, int filmsNew, int filmsUpdated, String error) {
        jdbc.sql("""
                        update crawl_run set finished = :now, films_new = :new, films_updated = :updated, error = :error
                        where id = :id
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("new", filmsNew).param("updated", filmsUpdated)
                .param("error", error).param("id", id).update();
    }

    /** The latest run of each source. */
    public List<Run> latest() {
        return jdbc.sql("""
                        select distinct on (source) * from crawl_run order by source, started desc
                        """)
                .query(CrawlRuns::run).list();
    }

    private static Run run(ResultSet rs, int row) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished");
        return new Run(rs.getString("source"), rs.getTimestamp("started").toInstant(),
                finished == null ? null : finished.toInstant(), rs.getInt("films_new"), rs.getInt("films_updated"),
                rs.getString("error"));
    }
}
