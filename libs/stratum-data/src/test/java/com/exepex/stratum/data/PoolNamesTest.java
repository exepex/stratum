package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.testsupport.NamedPoolTestData;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;

@DisplayName("A data source's pool name is HikariCP's pool name, or its bean name")
class PoolNamesTest {

  /** The data source bean's name. */
  private static final String BEAN = "reportingDataSource";

  /** Returns a pool that is never started, with the given pool name. */
  private HikariDataSource poolNamed(String poolName) {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(NamedPoolTestData.URL);
    pool.setPoolName(poolName);
    return pool;
  }

  @Nested
  @DisplayName("with HikariCP")
  class WithHikari {

    @Test
    @DisplayName("a HikariCP pool is named by its pool name")
    void hikariPoolName() {
      try (var pool = poolNamed(NamedPoolTestData.BATCH)) {
        assertThat(PoolNames.nameOf(pool, BEAN, Thread.currentThread().getContextClassLoader()))
            .isEqualTo(NamedPoolTestData.BATCH);
      }
    }
  }

  @Nested
  @DisplayName("without HikariCP")
  class WithoutHikari {

    @Test
    @DisplayName("a data source is named by its bean name, and HikariCP is never asked")
    void beanName() {
      try (var pool = poolNamed(NamedPoolTestData.BATCH)) {
        assertThat(PoolNames.nameOf(pool, BEAN, new FilteredClassLoader("com.zaxxer.hikari")))
            .isEqualTo(BEAN);
      }
    }
  }
}
