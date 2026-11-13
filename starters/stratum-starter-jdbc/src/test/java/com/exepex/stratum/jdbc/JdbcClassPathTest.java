package com.exepex.stratum.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataEnvironmentPostProcessor;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;
import org.springframework.util.ClassUtils;

@DisplayName("The JDBC starter brings plain JDBC and the shared data defaults, without an ORM")
class JdbcClassPathTest {

  @Test
  @DisplayName("Hibernate is not on the class path")
  void bringsNoHibernate() {
    assertThat(ClassUtils.isPresent("org.hibernate.Session", null)).isFalse();
  }

  @Test
  @DisplayName("the shared defaults of stratum-data are the only Stratum data defaults registered")
  void registersSharedDefaults() {
    var postProcessors =
        SpringFactoriesLoader.forDefaultResourceLocation()
            // Spring Boot's own post-processors need constructor arguments; they are skipped.
            .load(
                EnvironmentPostProcessor.class,
                null,
                FailureHandler.logging(LogFactory.getLog(getClass())))
            .stream()
            .map(postProcessor -> postProcessor.getClass().getName())
            .filter(name -> name.startsWith("com.exepex.stratum.data"))
            .toList();

    assertThat(postProcessors).containsExactly(DataEnvironmentPostProcessor.class.getName());
  }
}
