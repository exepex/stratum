package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.FlywayBoundaryCustomizer;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * Gives Flyway's own connection the clean errors of the JDBC boundary when the service uses Flyway,
 * unless {@code stratum.data.enabled=false}.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
@ConditionalOnClass(FlywayConfigurationCustomizer.class)
public final class FlywayBoundaryAutoConfiguration {

  /**
   * Wraps the connection Spring Boot builds for Flyway. A service replaces it with a bean of the
   * same name.
   *
   * @param boundary the post-processor whose boundary wraps the data source beans
   * @return the customizer
   */
  @Bean(DataConstants.FLYWAY_BOUNDARY)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.FLYWAY_BOUNDARY)
  public FlywayBoundaryCustomizer stratumFlywayBoundary(
      ObjectProvider<JdbcBoundaryPostProcessor> boundary) {
    return new FlywayBoundaryCustomizer(boundary);
  }
}
