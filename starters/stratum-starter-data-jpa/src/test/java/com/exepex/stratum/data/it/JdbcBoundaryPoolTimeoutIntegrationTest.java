package com.exepex.stratum.data.it;

import static com.exepex.stratum.data.testsupport.DataAssertions.hibernateTranslationOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.test.context.TestPropertySource;

@BoundaryServiceTest
@TestPropertySource(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.connection-timeout=250"
    })
@DisplayName("An exhausted pool's error keeps its class and message through the boundary")
class JdbcBoundaryPoolTimeoutIntegrationTest {

  @Autowired private DataSource dataSource;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  @DisplayName("the pool's timeout says so, and Spring and Hibernate translate it as before")
  void keepsPoolTimeout() throws SQLException {
    try (var _ = dataSource.getConnection()) {
      var failure = catchThrowableOfType(SQLException.class, dataSource::getConnection);

      assertThat((Throwable) failure)
          .isExactlyInstanceOf(SQLTransientConnectionException.class)
          .hasMessageContaining("Connection is not available, request timed out after");
      assertThat(new SQLExceptionSubclassTranslator().translate("probe", null, failure))
          .isInstanceOf(TransientDataAccessResourceException.class);
      assertThat(hibernateTranslationOf(entityManagerFactory, failure))
          .isInstanceOf(JDBCConnectionException.class);
    }
  }
}
