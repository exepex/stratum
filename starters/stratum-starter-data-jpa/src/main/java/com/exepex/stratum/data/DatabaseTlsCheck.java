package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.util.ClassUtils;

/**
 * Checks, once the application is ready, whether each data source bean's connection to the database
 * is encrypted, and logs one WARN for each that is not or that cannot be checked. The check runs on
 * its own daemon thread, so it never delays startup or readiness, and it can never stop the
 * service. Its lines carry no logging or tracing context, since the work belongs to no request. The
 * logger's name, this class's, is stable: set it to {@code error} to silence the check.
 */
@StratumInternal
@Slf4j
public class DatabaseTlsCheck implements SmartApplicationListener, DisposableBean {

  /** The name of the thread that runs the check. */
  static final String THREAD_NAME = "stratum-database-tls-check";

  /** The page that tells how to encrypt the connection. */
  private static final String DOCS =
      "https://github.com/exepex/stratum/blob/main/docs/modules/data-jpa-tls-check.md";

  /**
   * The line breaks a bean or database name could hold, replaced so that each message stays on one
   * line; the replacement is written in each log call, where SpotBugs sees it.
   */
  private static final String LINE_BREAKS = "[\r\n]";

  /** The driver class whose presence adds the PostgreSQL reader. */
  private static final String POSTGRES_DRIVER = "org.postgresql.Driver";

  /** The application whose data sources are checked; a child context's events are ignored. */
  private final ConfigurableApplicationContext context;

  /** The readers, one per kind of database. */
  private final List<EncryptionReader> readers;

  /** Whether the application is closing: no data source is checked or reported after that. */
  private final AtomicBoolean closing = new AtomicBoolean();

  /** The thread that runs the check, once started. */
  private final AtomicReference<@Nullable Thread> thread = new AtomicReference<>();

  /**
   * Creates the check with its readers.
   *
   * @param context the application whose data sources are checked
   * @param readers the readers, one per kind of database
   */
  DatabaseTlsCheck(ConfigurableApplicationContext context, List<EncryptionReader> readers) {
    this.context = context;
    this.readers = List.copyOf(readers);
  }

  /**
   * Creates the check with a reader for each database whose driver is on the class path.
   *
   * @param context the application whose data sources are checked
   * @return the check
   */
  public static DatabaseTlsCheck forClassPath(ConfigurableApplicationContext context) {
    List<EncryptionReader> readers =
        ClassUtils.isPresent(POSTGRES_DRIVER, context.getClassLoader())
            ? List.of(new PostgresEncryptionReader())
            : List.of();
    return new DatabaseTlsCheck(context, readers);
  }

  @Override
  public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    return ApplicationReadyEvent.class.isAssignableFrom(eventType)
        || ContextClosedEvent.class.isAssignableFrom(eventType);
  }

  /**
   * Starts the check when this application is ready, and stops it when it closes.
   *
   * @param event the ready or closed event of this application or of a child context
   */
  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    if (event instanceof ApplicationReadyEvent ready
        && context.equals(ready.getApplicationContext())) {
      start(dataSources());
    } else if (event instanceof ContextClosedEvent closed
        && context.equals(closed.getApplicationContext())) {
      close();
    }
  }

  /** Stops the check: nothing more is checked or reported. */
  @Override
  public void destroy() {
    close();
  }

  /**
   * Returns the data source beans that already exist, by name, without creating any bean: a lazy
   * data source not yet used is not checked.
   *
   * @return the data sources, by bean name
   */
  Map<String, DataSource> dataSources() {
    var beanFactory = context.getBeanFactory();
    var dataSources = new LinkedHashMap<String, DataSource>();
    for (var name : beanFactory.getBeanNamesForType(DataSource.class, false, false)) {
      if (beanFactory.containsSingleton(name)) {
        dataSources.put(name, beanFactory.getBean(name, DataSource.class));
      }
    }
    return dataSources;
  }

  /**
   * Checks the data sources on the check's own daemon thread. An error that ends the thread is
   * logged with its class only, and the service goes on.
   *
   * @param dataSources the data sources, by bean name
   */
  void start(Map<String, DataSource> dataSources) {
    var started =
        Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> checkAll(dataSources));
    started.setUncaughtExceptionHandler(
        (_, error) ->
            log.warn(
                ("The database TLS check stopped: " + error.getClass().getName())
                    .replaceAll(LINE_BREAKS, " ")));
    thread.set(started);
    started.start();
  }

  /**
   * Checks each data source in turn, until the application closes.
   *
   * @param dataSources the data sources, by bean name
   */
  void checkAll(Map<String, DataSource> dataSources) {
    for (var dataSource : dataSources.entrySet()) {
      if (closing.get()) {
        return;
      }
      checkDataSource(dataSource.getKey(), dataSource.getValue());
    }
  }

  /**
   * Checks one data source and logs its outcome. Any failure is logged as one WARN with its class
   * only, since a driver's message can carry the host, the port or the URL, and the check goes on.
   */
  private void checkDataSource(String name, DataSource dataSource) {
    try (var connection = dataSource.getConnection()) {
      var product = connection.getMetaData().getDatabaseProductName();
      var reader = readerOf(product);
      if (reader.isEmpty()) {
        logNotChecked(name, product);
      } else if (!reader.get().encrypted(connection) && !closing.get() && log.isWarnEnabled()) {
        log.warn(
            ("The data source '"
                    + name
                    + "' connects to the database without TLS. Set sslmode=verify-full in the JDBC"
                    + " URL of data source '"
                    + name
                    + "'. See "
                    + DOCS)
                .replaceAll(LINE_BREAKS, " "));
      }
    } catch (SQLException | RuntimeException failure) {
      if (!closing.get() && log.isWarnEnabled()) {
        log.warn(
            ("Could not check whether the data source '"
                    + name
                    + "' uses TLS: "
                    + failure.getClass().getName())
                .replaceAll(LINE_BREAKS, " "));
      }
    }
  }

  /** Logs at DEBUG that no reader knows the database, so the data source is not checked. */
  private void logNotChecked(String name, @Nullable String databaseProductName) {
    if (log.isDebugEnabled()) {
      log.debug(
          ("The data source '"
                  + name
                  + "' is not checked for TLS: no check for "
                  + databaseProductName)
              .replaceAll(LINE_BREAKS, " "));
    }
  }

  /** Returns the reader that knows the database, if any. */
  private Optional<EncryptionReader> readerOf(@Nullable String databaseProductName) {
    return databaseProductName == null
        ? Optional.empty()
        : readers.stream().filter(reader -> reader.reads(databaseProductName)).findFirst();
  }

  /** Marks the application as closing and wakes the thread from any wait. */
  private void close() {
    closing.set(true);
    var running = thread.get();
    if (running != null) {
      running.interrupt();
    }
  }
}
