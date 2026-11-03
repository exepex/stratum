package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.JdbcWrapper;
import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

@BoundaryServiceTest
@DisplayName("The boundary wraps Spring Boot's pool, which Boot's metrics and health still find")
class JdbcBoundaryWiringIntegrationTest {

  @Autowired private DataSource dataSource;

  @Autowired private List<MeterBinder> meterBinders;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("dbHealthContributor")
  private HealthIndicator databaseHealth;

  @Test
  @DisplayName("with Boot's eager connection fetch, the data source bean is the boundary")
  void wrapsBootPool() throws SQLException {
    assertThat(dataSource).isInstanceOf(JdbcWrapper.class);
    assertThat(dataSource.unwrap(HikariDataSource.class)).isNotNull();
  }

  @Test
  @DisplayName("Boot's pool metrics find the pool behind the boundary")
  void findsPoolForMetrics() {
    meterBinders.forEach(binder -> binder.bindTo(meterRegistry));

    assertThat(meterRegistry.find("hikaricp.connections").gauge()).isNotNull();
  }

  @Test
  @DisplayName("Boot's db health check reaches the database through the boundary")
  void reportsDatabaseHealth() {
    assertThat(databaseHealth.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("the pool behind the boundary closes when the application stops")
  void closesPoolAtShutdown() throws SQLException {
    DataSource pool;
    try (var application =
        new SpringApplicationBuilder(
                BoundaryApplication.class, PostgresContainerConfiguration.class)
            .web(WebApplicationType.NONE)
            .properties("spring.jpa.hibernate.ddl-auto=none")
            .run()) {
      pool = application.getBean(DataSource.class).unwrap(HikariDataSource.class);
    }

    assertThatThrownBy(pool::getConnection).hasMessageContaining("has been closed");
  }
}
