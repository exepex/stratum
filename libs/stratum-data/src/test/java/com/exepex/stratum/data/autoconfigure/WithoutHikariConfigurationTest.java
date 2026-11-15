package com.exepex.stratum.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcWrapper;
import com.exepex.stratum.data.testsupport.DataStarterStandIns;
import com.exepex.stratum.data.testsupport.NamedPoolTestData;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

@DisplayName("A service without HikariCP starts, and its data sources still get the boundary")
class WithoutHikariConfigurationTest {

  /** Returns the application with a plain data source and HikariCP off the class path. */
  private ApplicationContextRunner withoutHikari() {
    return new ApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader("com.zaxxer.hikari"))
        .withConfiguration(AutoConfigurations.of(DataStarterStandIns.JdbcBoundaryStandIn.class))
        .withUserConfiguration(PlainDataSource.class);
  }

  @Nested
  @DisplayName("startup")
  class Startup {

    @Test
    @DisplayName("the application starts without the beans that need HikariCP")
    void startsWithoutHikariBeans() {
      withoutHikari().run(context -> assertStartsWithoutHikariBeans(context));
    }

    @Test
    @DisplayName("the application starts with a named pool's key set, without building the pool")
    void startsWithPoolKey() {
      withoutHikari()
          .withPropertyValues(NamedPoolTestData.BATCH_KEYS + "url=" + NamedPoolTestData.URL)
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("batchDataSource"));
    }

    /** Checks what startsWithoutHikariBeans expects of the started application. */
    private void assertStartsWithoutHikariBeans(AssertableApplicationContext context) {
      assertThat(context)
          .hasNotFailed()
          .doesNotHaveBean(DataConstants.NAMED_POOLS)
          .doesNotHaveBean(DataConstants.MAIN_POOL_NAME)
          .doesNotHaveBean(DataConstants.POOL_CHECK)
          .doesNotHaveBean(DataConstants.LOGIN_TIMEOUT)
          .hasBean(DataConstants.TLS_CHECK);
    }
  }

  @Nested
  @DisplayName("the boundary")
  class Boundary {

    @Test
    @DisplayName("a plain data source bean gets the boundary")
    void plainDataSourceWrapped() {
      withoutHikari()
          .run(
              context ->
                  assertThat(context.getBean(DataConstants.MAIN_BEAN, DataSource.class))
                      .isInstanceOf(JdbcWrapper.class));
    }
  }

  /** A service's own data source, which is no HikariCP pool. */
  @Configuration(proxyBeanMethods = false)
  static class PlainDataSource {

    /**
     * Returns a data source that never connects in these tests.
     *
     * @return the data source
     */
    @Bean(DataConstants.MAIN_BEAN)
    DataSource dataSource() {
      return new SimpleDriverDataSource();
    }
  }
}
