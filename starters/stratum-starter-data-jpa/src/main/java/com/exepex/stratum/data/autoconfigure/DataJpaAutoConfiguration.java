package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.SchemaValidationDefault;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * Creates the data module's beans when Spring Boot sets up Hibernate, unless {@code
 * stratum.data.enabled=false}.
 */
@StratumInternal
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
@ConditionalOnBean(HibernateProperties.class)
public final class DataJpaAutoConfiguration {

  /**
   * Makes Hibernate check the schema at startup and never change it, unless the service chose a
   * schema setting or the database is in memory. A service replaces it with a bean of the same
   * name.
   *
   * @param jpaProperties the service's {@code spring.jpa.*} settings
   * @param hibernateProperties the service's {@code spring.jpa.hibernate.*} settings
   * @param dataSource the database Hibernate uses
   * @return the customizer, which Spring Boot applies to Hibernate's settings
   */
  @Bean(DataConstants.SCHEMA_VALIDATION_DEFAULT)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.SCHEMA_VALIDATION_DEFAULT)
  public SchemaValidationDefault schemaValidationDefault(
      JpaProperties jpaProperties, HibernateProperties hibernateProperties, DataSource dataSource) {
    return new SchemaValidationDefault(jpaProperties, hibernateProperties, dataSource);
  }
}
