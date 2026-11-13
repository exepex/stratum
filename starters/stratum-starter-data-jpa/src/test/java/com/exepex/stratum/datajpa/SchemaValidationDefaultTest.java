package com.exepex.stratum.datajpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.data.testsupport.DataTestData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.core.Ordered;

@DisplayName("SchemaValidationDefault makes Hibernate validate the schema unless the service chose")
class SchemaValidationDefaultTest {

  private static final String HBM2DDL_AUTO = "hibernate.hbm2ddl.auto";

  /** Spelled out, so a wrong value in the module fails the tests. */
  private static final String VALIDATE = "validate";

  private static final String DATABASE_ACTION =
      "jakarta.persistence.schema-generation.database.action";

  private final JpaProperties jpaProperties = new JpaProperties();

  private final HibernateProperties hibernateProperties = new HibernateProperties();

  /** Returns the settings Hibernate starts with after the customizer ran on Boot's settings. */
  private Map<String, Object> customize(DataSource dataSource, Map<String, Object> bootSettings) {
    var hibernateSettings = new HashMap<>(bootSettings);
    new SchemaValidationDefault(jpaProperties, hibernateProperties, dataSource)
        .customize(hibernateSettings);
    return hibernateSettings;
  }

  @Nested
  @DisplayName("when the service chose no schema setting")
  class NoServiceChoice {

    @Test
    @DisplayName("with no schema setting, a database on a server is validated")
    void validatesServerDatabase() throws SQLException {
      assertThat(customize(DataTestData.serverDatabase(), Map.of()))
          .containsEntry(HBM2DDL_AUTO, VALIDATE);
    }

    @Test
    @DisplayName("an explicit spring.jpa.generate-ddl=false still gets validate")
    void validatesWithGenerateDdlOff() throws SQLException {
      jpaProperties.setGenerateDdl(false);

      assertThat(customize(DataTestData.serverDatabase(), Map.of()))
          .containsEntry(HBM2DDL_AUTO, VALIDATE);
    }

    @Test
    @DisplayName("an in-memory database keeps Spring Boot's default")
    void keepsBootDefaultInMemory() throws SQLException {
      assertThat(customize(DataTestData.inMemoryDatabase(), Map.of(HBM2DDL_AUTO, "create-drop")))
          .containsExactly(Map.entry(HBM2DDL_AUTO, "create-drop"));
    }
  }

  @Nested
  @DisplayName("among Spring Boot's customizers")
  class Ordering {

    @Test
    @DisplayName("it runs before the service's own customizers, so theirs win")
    void runsFirst() throws SQLException {
      assertThat(
              new SchemaValidationDefault(
                      jpaProperties, hibernateProperties, DataTestData.serverDatabase())
                  .getOrder())
          .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
  }

  @Nested
  @DisplayName("when the service chose a schema setting, it changes nothing")
  class ServiceChoice {

    /**
     * Each schema setting a service can make, as Spring Boot binds it, including "none", which
     * Spring Boot removes from Hibernate's settings before the customizers run.
     */
    private static final List<Arguments> SERVICE_SETTINGS =
        List.of(
            arguments(
                "spring.jpa.properties.hibernate.hbm2ddl.auto=none",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> jpa.getProperties().put(HBM2DDL_AUTO, "none")),
            arguments(
                "spring.jpa.properties.hibernate.hbm2ddl.auto=update",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> jpa.getProperties().put(HBM2DDL_AUTO, "update")),
            arguments(
                "spring.jpa.hibernate.ddl-auto=none",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> hibernate.setDdlAuto("none")),
            arguments(
                "spring.jpa.hibernate.ddl-auto=update",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> hibernate.setDdlAuto("update")),
            arguments(
                "jakarta.persistence.schema-generation.database.action=create",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> jpa.getProperties().put(DATABASE_ACTION, "create")),
            arguments(
                "spring.jpa.generate-ddl=true",
                (BiConsumer<JpaProperties, HibernateProperties>)
                    (jpa, hibernate) -> jpa.setGenerateDdl(true)));

    @ParameterizedTest(name = "{0}")
    @FieldSource("SERVICE_SETTINGS")
    @DisplayName("the service's setting is kept")
    void keepsServiceSetting(
        String setting, BiConsumer<JpaProperties, HibernateProperties> serviceSetting)
        throws SQLException {
      serviceSetting.accept(jpaProperties, hibernateProperties);

      assertThat(customize(DataTestData.serverDatabase(), Map.of()))
          .doesNotContainKey(HBM2DDL_AUTO);
    }
  }
}
