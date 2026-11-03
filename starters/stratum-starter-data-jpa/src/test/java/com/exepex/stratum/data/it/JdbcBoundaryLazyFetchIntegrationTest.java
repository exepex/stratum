package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.JdbcWrapper;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.TestPropertySource;

@BoundaryServiceTest
@TestPropertySource(properties = "spring.datasource.connection-fetch=lazy")
@DisplayName("With Boot's lazy connection fetch, Boot's proxy wraps the boundary")
class JdbcBoundaryLazyFetchIntegrationTest {

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Boot's lazy proxy is the outermost bean, and the boundary is inside it")
  void putsLazyProxyOutside() {
    assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
    assertThat(((LazyConnectionDataSourceProxy) dataSource).getTargetDataSource())
        .isInstanceOf(JdbcWrapper.class);
  }

  @Test
  @DisplayName("no pooled connection is taken until the first statement")
  void takesNoConnectionBeforeFirstStatement() throws SQLException {
    var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
    try (var connection = dataSource.getConnection()) {
      assertThat(pool.getActiveConnections()).isZero();

      try (var _ = connection.prepareStatement("SELECT 1")) {
        assertThat(pool.getActiveConnections()).isOne();
      }
    }
  }
}
