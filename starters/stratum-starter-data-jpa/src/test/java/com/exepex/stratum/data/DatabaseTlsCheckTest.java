package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.test.LogLineCapture;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

@DisplayName("DatabaseTlsCheck warns about each data source whose connection is not encrypted")
class DatabaseTlsCheckTest {

  /** The logger's name, which services set to silence the check: it must never change. */
  private static final String LOGGER = "com.exepex.stratum.data.DatabaseTlsCheck";

  /** The name of Spring Boot's data source bean. */
  private static final String DATA_SOURCE = "dataSource";

  /** The name the PostgreSQL driver gives the database. */
  private static final String POSTGRESQL = "PostgreSQL";

  /** A driver message with the details that must never reach the log. */
  private static final String SECRET_MESSAGE =
      "connect to jdbc:postgresql://db.internal:5432/orders?password=hunter2 failed";

  /** How long a test watches for a check that must not start, in milliseconds. */
  private static final long QUIET_MILLIS = 200;

  private final LogLineCapture lines = new LogLineCapture(LOGGER);

  private final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);

  private final EncryptionReader reader = mock(EncryptionReader.class);

  private final GenericApplicationContext context = new GenericApplicationContext();

  private final DatabaseTlsCheck check = new DatabaseTlsCheck(context, List.of(reader));

  /** Makes the reader know PostgreSQL and starts capturing the check's lines. */
  @BeforeEach
  void readPostgres() {
    when(reader.reads(POSTGRESQL)).thenReturn(true);
    lines.start();
  }

  /** Stops the capture, resets the logger's level and closes the context. */
  @AfterEach
  void stopCapture() {
    lines.stop();
    logger.setLevel(null);
    context.close();
  }

  /** Returns a data source whose connections are to a database of the given name. */
  private DataSource dataSourceOf(String databaseProductName) throws SQLException {
    var metaData = mock(DatabaseMetaData.class);
    when(metaData.getDatabaseProductName()).thenReturn(databaseProductName);
    var connection = mock(Connection.class);
    when(connection.getMetaData()).thenReturn(metaData);
    var dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    return dataSource;
  }

  /** Returns the data sources by name, in the order given. */
  private Map<String, DataSource> named(String name, DataSource dataSource) {
    var dataSources = new LinkedHashMap<String, DataSource>();
    dataSources.put(name, dataSource);
    return dataSources;
  }

  /** Returns the messages of the captured lines. */
  private List<String> messages() {
    return lines.lines().stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Nested
  @DisplayName("what it logs")
  class Outcomes {

    @Test
    @DisplayName("an encrypted connection logs nothing")
    void encrypted() throws SQLException {
      when(reader.encrypted(any())).thenReturn(true);

      check.checkAll(named(DATA_SOURCE, dataSourceOf(POSTGRESQL)));

      assertThat(lines.lines()).isEmpty();
    }

    @Test
    @DisplayName("each unencrypted data source gets one WARN naming its bean, not Boot's key")
    void unencryptedNamesEachBean() throws SQLException {
      var dataSources = named(DATA_SOURCE, dataSourceOf(POSTGRESQL));
      dataSources.put("orders", dataSourceOf(POSTGRESQL));

      check.checkAll(dataSources);

      assertThat(lines.lines()).extracting(ILoggingEvent::getLevel).containsOnly(Level.WARN);
      assertThat(messages())
          .hasSize(2)
          .allSatisfy(message -> assertThat(message).doesNotContain("spring.datasource.url"));
      assertThat(messages().get(1))
          .contains("data source 'orders' connects to the database without TLS")
          .contains("JDBC URL of data source 'orders'");
    }

    @Test
    @DisplayName("a reader failure gives one WARN with the error's class only, never its message")
    void failureLogsClassOnly() throws SQLException {
      when(reader.encrypted(any())).thenThrow(new SQLException(SECRET_MESSAGE));

      check.checkAll(named("orders", dataSourceOf(POSTGRESQL)));

      var line = lines.onlyLine();
      assertThat(line.getLevel()).isEqualTo(Level.WARN);
      assertThat(line.getFormattedMessage())
          .isEqualTo(
              "Could not check whether the data source 'orders' uses TLS: "
                  + SQLException.class.getName());
      assertThat(line.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("a pool that fails with any runtime error is reported, and the next is checked")
    void runtimeFailureGoesOn() throws SQLException {
      var broken = mock(DataSource.class);
      when(broken.getConnection()).thenThrow(new IllegalStateException(SECRET_MESSAGE));
      var dataSources = named("broken", broken);
      dataSources.put("orders", dataSourceOf(POSTGRESQL));

      check.checkAll(dataSources);

      assertThat(messages())
          .hasSize(2)
          .noneMatch(message -> message.contains("hunter2") || message.contains("db.internal"));
      assertThat(messages().getFirst())
          .isEqualTo(
              "Could not check whether the data source 'broken' uses TLS: "
                  + IllegalStateException.class.getName());
      assertThat(messages().getLast()).contains("'orders' connects to the database without TLS");
    }

    @Test
    @DisplayName("a bean name with a line break stays on one log line")
    void oneLinePerWarn() throws SQLException {
      check.checkAll(named("orders\nFAKE LINE", dataSourceOf(POSTGRESQL)));

      assertThat(lines.onlyLine().getFormattedMessage()).doesNotContain("\n", "\r");
    }

    @Test
    @DisplayName("a database no reader knows is not checked, and logged at DEBUG only")
    void unknownDatabase() throws SQLException {
      logger.setLevel(Level.DEBUG);

      check.checkAll(named(DATA_SOURCE, dataSourceOf("H2")));

      assertThat(lines.onlyLine().getLevel()).isEqualTo(Level.DEBUG);
      verify(reader, never()).encrypted(any());
    }
  }

  @Nested
  @DisplayName("how a service silences it")
  class Silencing {

    /** The profiles that design 6.7 once exempted; the owner decided the WARN applies in them. */
    private static final List<String> FORMERLY_EXEMPT_PROFILES = List.of("local", "test");

    @ParameterizedTest
    @FieldSource("FORMERLY_EXEMPT_PROFILES")
    @DisplayName("no profile silences it: the WARN appears with the local and test profiles")
    void warnsInEveryProfile(String profile) throws SQLException {
      context.getEnvironment().setActiveProfiles(profile);

      check.checkAll(named(DATA_SOURCE, dataSourceOf(POSTGRESQL)));

      assertThat(lines.onlyLine().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("its logger is the class's own name, and level error silences both WARNs")
    void loggerLevelSilences() throws SQLException {
      assertThat(DatabaseTlsCheck.class.getName()).isEqualTo(LOGGER);
      logger.setLevel(Level.ERROR);
      var broken = mock(DataSource.class);
      when(broken.getConnection()).thenThrow(new SQLException(SECRET_MESSAGE));
      var dataSources = named("broken", broken);
      dataSources.put(DATA_SOURCE, dataSourceOf(POSTGRESQL));

      check.checkAll(dataSources);

      assertThat(lines.lines()).isEmpty();
    }
  }

  @Nested
  @DisplayName("when and where it runs")
  class Running {

    /** Returns the ready event of an application context. */
    private ApplicationReadyEvent readyEventOf(GenericApplicationContext readyContext) {
      return new ApplicationReadyEvent(
          new SpringApplication(), new String[0], readyContext, Duration.ZERO);
    }

    @Test
    @DisplayName("the ready event starts it on its own thread, never on the event's thread")
    void runsOnItsOwnThread() throws SQLException {
      var dataSource = dataSourceOf(POSTGRESQL);
      context.registerBean(DATA_SOURCE, DataSource.class, () -> dataSource);
      context.refresh();

      check.onApplicationEvent(readyEventOf(context));

      assertThat(lines.onlyLine().getThreadName()).isEqualTo(DatabaseTlsCheck.THREAD_NAME);
    }

    @Test
    @DisplayName("a child context's ready event does not start it")
    void ignoresChildContext() throws SQLException {
      var dataSource = dataSourceOf(POSTGRESQL);
      context.registerBean(DATA_SOURCE, DataSource.class, () -> dataSource);
      context.refresh();
      try (var child = new GenericApplicationContext(context)) {
        child.refresh();

        check.onApplicationEvent(readyEventOf(child));

        verify(dataSource, after(QUIET_MILLIS).never()).getConnection();
      }
    }

    @Test
    @DisplayName("an error that ends the thread gives one WARN with its class only")
    void errorEndsThreadOnly() throws SQLException {
      when(reader.encrypted(any())).thenThrow(new LinkageError(SECRET_MESSAGE));

      check.start(named(DATA_SOURCE, dataSourceOf(POSTGRESQL)));

      assertThat(lines.onlyLine().getFormattedMessage())
          .isEqualTo("The database TLS check stopped: " + LinkageError.class.getName());
    }

    /** Closes the application while a data source is being checked, and answers unencrypted. */
    private boolean closeWhileChecking() {
      check.onApplicationEvent(new ContextClosedEvent(context));
      return false;
    }

    @Test
    @DisplayName("once the application closes, nothing more is checked or reported")
    void closingStopsTheCheck() throws SQLException {
      when(reader.encrypted(any())).thenAnswer(ignored -> closeWhileChecking());
      var later = dataSourceOf(POSTGRESQL);
      var dataSources = named("closing", dataSourceOf(POSTGRESQL));
      dataSources.put("later", later);

      check.checkAll(dataSources);

      assertThat(lines.lines()).isEmpty();
      verify(later, never()).getConnection();
    }

    @Test
    @DisplayName("once the bean is destroyed, no data source is checked")
    void destroyedChecksNothing() throws SQLException {
      var dataSource = dataSourceOf(POSTGRESQL);

      check.destroy();
      check.checkAll(named(DATA_SOURCE, dataSource));

      verify(dataSource, never()).getConnection();
    }
  }

  @Nested
  @DisplayName("which data sources it checks")
  class Choice {

    /** Whether the lazy data source was created. */
    private final AtomicBoolean created = new AtomicBoolean();

    /** Creates the lazy data source and remembers that it was created. */
    private DataSource createLazy() {
      created.set(true);
      return mock(DataSource.class);
    }

    @Test
    @DisplayName("only data sources that already exist: a lazy one is not created")
    void skipsLazyDataSource() {
      context.registerBean(DATA_SOURCE, DataSource.class, () -> mock(DataSource.class));
      context.registerBean(
          "lazyDataSource",
          DataSource.class,
          this::createLazy,
          definition -> definition.setLazyInit(true));
      context.refresh();

      assertThat(check.dataSources()).containsOnlyKeys(DATA_SOURCE);
      assertThat(created).isFalse();
    }
  }
}
