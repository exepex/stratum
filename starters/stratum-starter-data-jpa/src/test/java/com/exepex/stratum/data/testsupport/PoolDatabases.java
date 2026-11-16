package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.test.NamedPoolConnections;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two databases of the tests of repositories on a pool: main's, whose notes table Spring Boot's
 * SQL init creates at startup, and the pool {@code batch}'s, whose table {@link #reset} creates.
 * Each is read through its own pool, never through the routing data source.
 */
public final class PoolDatabases {

  /** The pool the tests map repositories to. */
  public static final String BATCH = "batch";

  /**
   * The property that maps the repositories of the package {@code pools.batch} to {@code batch}.
   */
  public static final String MAPPING =
      "stratum.data.sources.batch.packages=com.exepex.stratum.data.it.pools.batch";

  /** The property that runs Spring Boot's SQL init on main at startup. */
  public static final String SQL_INIT = "spring.sql.init.mode=always";

  /** The property that names main's schema, with the notes table. */
  public static final String SCHEMA = "spring.sql.init.schema-locations=classpath:pools-schema.sql";

  /** The statement that creates the notes table, if it is missing. */
  private static final String CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS notes (id UUID PRIMARY KEY, text VARCHAR(255) NOT NULL)";

  /** Main's database, through Spring Boot's own data source. */
  private final JdbcClient main;

  /** The pool {@code batch}'s database, through its own data source. */
  private final JdbcClient batch;

  /** The started service. */
  private final ApplicationContext application;

  /**
   * Reads both databases of a started service.
   *
   * @param application the service
   */
  public PoolDatabases(ApplicationContext application) {
    this.application = application;
    main = JdbcClient.create(application.getBean("dataSource", DataSource.class));
    batch = JdbcClient.create(application.getBean("batchDataSource", DataSource.class));
  }

  /** Gives {@code batch} the notes table, and empties it on both databases. */
  public void reset() {
    batch.sql(CREATE_TABLE).update();
    main.sql("TRUNCATE notes").update();
    batch.sql("TRUNCATE notes").update();
  }

  /**
   * Returns the number of notes on main's database.
   *
   * @return the count
   */
  public long mainRows() {
    return main.sql("SELECT count(*) FROM notes").query(Long.class).single();
  }

  /**
   * Returns the number of notes on the pool {@code batch}'s database.
   *
   * @return the count
   */
  public long batchRows() {
    return batch.sql("SELECT count(*) FROM notes").query(Long.class).single();
  }

  /**
   * Returns the text of a note on the pool {@code batch}'s database.
   *
   * @param id the note's id
   * @return its text
   */
  public String batchText(UUID id) {
    return batch.sql("SELECT text FROM notes WHERE id = ?").param(id).query(String.class).single();
  }

  /**
   * Returns how many connections a pool has lent out right now.
   *
   * @param beanName the pool's data source bean
   * @return the count
   * @throws SQLException if the bean is no HikariCP pool behind Stratum's boundary
   */
  public int activeConnections(String beanName) throws SQLException {
    return application
        .getBean(beanName, DataSource.class)
        .unwrap(HikariDataSource.class)
        .getHikariPoolMXBean()
        .getActiveConnections();
  }

  /** Gives the pool {@code batch} its own PostgreSQL database. */
  public static final class BatchDatabase
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      NamedPoolConnections.postgres(BATCH).initialize(context);
    }
  }
}
