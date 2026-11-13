package com.exepex.stratum.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataEnvironmentPostProcessor;
import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

@DisplayName("The JDBC starter brings plain JDBC and the shared data defaults, without an ORM")
class JdbcClassPathTest {

  @Nested
  @DisplayName("the ORM")
  class Orm {

    @Test
    @DisplayName("Hibernate is not on the class path")
    void bringsNoHibernate() {
      assertThat(ClassUtils.isPresent("org.hibernate.Session", null)).isFalse();
    }
  }

  @Nested
  @DisplayName("the environment post-processors")
  class PostProcessors {

    @Test
    @DisplayName(
        "the shared defaults of stratum-data are the only Stratum data defaults registered")
    void registersSharedDefaults() {
      var postProcessors =
          StarterTestSupport.environmentPostProcessors().stream()
              .map(postProcessor -> postProcessor.getClass().getName())
              .filter(name -> name.startsWith("com.exepex.stratum.data"))
              .toList();

      assertThat(postProcessors).containsExactly(DataEnvironmentPostProcessor.class.getName());
    }
  }
}
