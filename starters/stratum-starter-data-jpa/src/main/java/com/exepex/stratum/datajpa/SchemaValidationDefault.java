package com.exepex.stratum.datajpa;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.SchemaToolingSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.core.Ordered;

/**
 * Makes Hibernate check at startup that the entities match the database schema, and never change
 * the schema, when the service chose no schema setting of its own: none of {@code
 * hibernate.hbm2ddl.auto}, {@code spring.jpa.hibernate.ddl-auto}, {@code
 * jakarta.persistence.schema-generation.database.action} and {@code spring.jpa.generate-ddl=true}.
 * An in-memory database keeps Spring Boot's default. It runs before the service's own customizers,
 * so theirs win.
 */
@StratumInternal
@RequiredArgsConstructor
public class SchemaValidationDefault implements HibernatePropertiesCustomizer, Ordered {

  /** The service's {@code spring.jpa.*} settings. */
  private final JpaProperties jpaProperties;

  /** The service's {@code spring.jpa.hibernate.*} settings. */
  private final HibernateProperties hibernateProperties;

  /** The database the settings are for. */
  private final DataSource dataSource;

  /**
   * Sets {@code hibernate.hbm2ddl.auto=validate} unless the service chose a schema setting or the
   * database is in memory.
   *
   * @param hibernateSettings the settings Hibernate starts with, after Spring Boot's defaults
   */
  @Override
  public void customize(Map<String, Object> hibernateSettings) {
    // Spring Boot removes "none" from these settings, so the service's choice is read from its
    // bound properties instead.
    if (serviceChoseSchemaSetting() || EmbeddedDatabaseConnection.isEmbedded(dataSource)) {
      return;
    }
    hibernateSettings.put(SchemaToolingSettings.HBM2DDL_AUTO, DataJpaConstants.VALIDATE);
  }

  /**
   * Runs first among the customizers, so a service's own customizer can still change the setting.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  /** Returns whether the service set any of the keys Spring Boot reads for the schema setting. */
  private boolean serviceChoseSchemaSetting() {
    var jpaSettings = jpaProperties.getProperties();
    return jpaSettings.containsKey(SchemaToolingSettings.HBM2DDL_AUTO)
        || hibernateProperties.getDdlAuto() != null
        || jpaSettings.containsKey(SchemaToolingSettings.JAKARTA_HBM2DDL_DATABASE_ACTION)
        || jpaProperties.isGenerateDdl();
  }
}
