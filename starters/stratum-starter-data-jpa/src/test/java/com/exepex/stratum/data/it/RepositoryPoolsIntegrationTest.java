package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.it.pools.Note;
import com.exepex.stratum.data.it.pools.PoolsApplication;
import com.exepex.stratum.data.it.pools.batch.BatchNotes;
import com.exepex.stratum.data.it.pools.batch.CachedNote;
import com.exepex.stratum.data.it.pools.batch.CachedNotes;
import com.exepex.stratum.data.it.pools.batch.MainPinnedNotes;
import com.exepex.stratum.data.it.pools.main.AnnotatedNotes;
import com.exepex.stratum.data.it.pools.main.MainNotes;
import com.exepex.stratum.data.testsupport.PoolDatabases;
import com.exepex.stratum.datajpa.routing.RepositoryPoolAdvice;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.TracedRequests;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.SQLException;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = {
      PoolsApplication.class,
      PostgresContainerConfiguration.class,
      QuerySpansIntegrationTest.Spans.class
    },
    properties = {PoolDatabases.MAPPING, PoolDatabases.SQL_INIT, PoolDatabases.SCHEMA})
@ContextConfiguration(initializers = PoolDatabases.BatchDatabase.class)
@DisplayName("On PostgreSQL, each JPA repository writes to the pool its package or annotation maps")
class RepositoryPoolsIntegrationTest {

  @Autowired private ApplicationContext application;

  @Autowired private TransactionTemplate transactions;

  /** The traced requests, which the application context owns and closes. */
  @Autowired private TracedRequests traced;

  @Autowired private MainNotes mainNotes;

  @Autowired private BatchNotes batchNotes;

  @Autowired private AnnotatedNotes annotatedNotes;

  @Autowired private MainPinnedNotes mainPinnedNotes;

  /** Both databases, read through their own pools. */
  private PoolDatabases databases;

  /** Empties both databases. */
  @BeforeEach
  void reset() {
    databases = new PoolDatabases(application);
    databases.reset();
  }

  @Nested
  @DisplayName("where a repository writes")
  class Placement {

    @Test
    @DisplayName("a repository in the mapped package writes to its pool, with no transaction")
    void mappedPackage() {
      batchNotes.save(new Note(null, "batch"));

      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }

    @Test
    @DisplayName("a repository in no mapped package writes to main")
    void unmappedPackage() {
      mainNotes.save(new Note(null, "main"));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isZero();
    }

    @Test
    @DisplayName("@UseDataSource(\"batch\") in no mapped package writes to batch")
    void annotationNamesPool() {
      annotatedNotes.save(new Note(null, "annotated"));

      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }

    @Test
    @DisplayName("@UseDataSource(\"main\") in the mapped package wins and writes to main")
    void annotationWinsOverPackage() {
      mainPinnedNotes.save(new Note(null, "pinned"));

      assertThat(databases.mainRows()).isOne();
      assertThat(databases.batchRows()).isZero();
    }
  }

  @Nested
  @DisplayName("writes Hibernate makes at commit")
  class CommitWrites {

    @Test
    @DisplayName("a save in a transaction, flushed only at commit, lands on the pool")
    void deferredSave() {
      transactions.executeWithoutResult(status -> batchNotes.save(new Note(null, "deferred")));

      assertThat(databases.batchRows()).isOne();
      assertThat(databases.mainRows()).isZero();
    }

    @Test
    @DisplayName("a loaded note changed in a transaction is updated on the pool at commit")
    void loadModifyCommit() {
      var id = batchNotes.save(new Note(null, "before")).getId();

      transactions.executeWithoutResult(
          status -> batchNotes.findById(id).orElseThrow().setText("after"));

      assertThat(databases.batchText(id)).isEqualTo("after");
    }

    @Test
    @DisplayName("a loaded note deleted in a transaction is deleted on the pool at commit")
    void deleteLoaded() {
      var id = batchNotes.save(new Note(null, "doomed")).getId();

      transactions.executeWithoutResult(
          status -> batchNotes.delete(batchNotes.findById(id).orElseThrow()));

      assertThat(databases.batchRows()).isZero();
    }
  }

  @Nested
  @DisplayName("repositories of other Spring Data modules")
  class OtherModules {

    @Test
    @DisplayName("a map repository in the mapped package gets no pool advice, and still works")
    void mapRepositoryUntouched() {
      var cached = application.getBean(CachedNotes.class);
      cached.save(new CachedNote("1", "cached"));

      assertThat(Arrays.stream(((Advised) cached).getAdvisors()))
          .noneMatch(advisor -> advisor.getAdvice() instanceof RepositoryPoolAdvice);
      assertThat(Arrays.stream(((Advised) batchNotes).getAdvisors()))
          .anyMatch(advisor -> advisor.getAdvice() instanceof RepositoryPoolAdvice);
      assertThat(cached.findById("1")).isPresent();
    }
  }

  @Nested
  @DisplayName("the data source beans")
  class Beans {

    @Test
    @DisplayName("a plain DataSource is the routing proxy; dataSource still unwraps to main's pool")
    void plainDataSourceRoutes() throws SQLException {
      var plain = application.getBean(DataSource.class);

      assertThat(application.getBean(DataConstants.ROUTING_BEAN)).isSameAs(plain);
      assertThatThrownBy(() -> plain.unwrap(HikariDataSource.class))
          .isInstanceOf(SQLException.class);
      assertThat(
              application
                  .getBean(DataConstants.MAIN_BEAN, DataSource.class)
                  .unwrap(HikariDataSource.class)
                  .getPoolName())
          .isEqualTo(DataConstants.MAIN_POOL);
    }

    @Test
    @DisplayName("Spring Boot's db health lists each pool, and not the routing data source")
    void healthSkipsRouter() {
      var health = application.getBean("dbHealthContributor", CompositeHealthContributor.class);

      assertThat(health.getContributor(DataConstants.MAIN_BEAN)).isNotNull();
      assertThat(health.getContributor("batchDataSource")).isNotNull();
      assertThat(health.getContributor(DataConstants.ROUTING_BEAN)).isNull();
    }

    @Test
    @DisplayName("Spring Boot's pool metrics name each pool, and no meter names the routing bean")
    void metricsSkipRouter() {
      var registry = application.getBean(MeterRegistry.class);
      application
          .getBeansOfType(MeterBinder.class)
          .values()
          .forEach(binder -> binder.bindTo(registry));

      assertThat(registry.find("hikaricp.connections.max").tag("pool", PoolDatabases.BATCH).gauge())
          .isNotNull();
      assertThat(registry.getMeters())
          .noneMatch(meter -> meter.getId().toString().contains("stratumRouting"));
    }

    @Test
    @DisplayName("a routed statement passes one boundary: one span, which names its pool")
    void oneBoundary() throws SQLException {
      traced.reset();

      traced.inRequest(() -> batchNotes.count());

      assertThat(traced.statementSpans())
          .singleElement()
          .extracting(
              span -> span.getAttributes().get(com.exepex.stratum.test.QuerySpanAssertions.POOL))
          .isEqualTo(PoolDatabases.BATCH);
    }
  }
}
