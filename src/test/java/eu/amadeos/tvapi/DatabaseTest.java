package eu.amadeos.tvapi;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static eu.amadeos.tvapi.repository.jooq.Tables.CRAWL_RUN;
import static eu.amadeos.tvapi.repository.jooq.Tables.FILM;

/**
 * Base of the tests with the database: the whole program with a web server on a free port and PostgreSQL
 * in a container, shared by all of them; every test starts with empty tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
public abstract class DatabaseTest {
    @Autowired
    protected DSLContext dsl;

    @BeforeEach
    void emptyTables() {
        dsl.truncate(FILM, CRAWL_RUN).execute();
    }
}
