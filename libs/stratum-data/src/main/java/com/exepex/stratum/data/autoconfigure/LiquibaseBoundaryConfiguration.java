package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import com.exepex.stratum.data.LiquibaseBoundaryPostProcessor;
import liquibase.integration.spring.SpringLiquibase;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/**
 * Gives Liquibase's own connection the clean errors of the JDBC boundary when the service uses
 * Liquibase, unless {@code stratum.data.enabled=false}. Each data starter's auto-configuration
 * imports it.
 */
@StratumInternal
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(DataConstants.MODULE)
@ConditionalOnClass(SpringLiquibase.class)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LiquibaseBoundaryConfiguration {

  /**
   * Wraps the connection Spring Boot builds for Liquibase. A service replaces it with a bean of the
   * same name. Static, because Spring creates bean post-processors before the other beans.
   *
   * @param boundary the post-processor whose boundary wraps the data source beans
   * @return the post-processor
   */
  @Bean(DataConstants.LIQUIBASE_BOUNDARY)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.LIQUIBASE_BOUNDARY)
  public static LiquibaseBoundaryPostProcessor stratumLiquibaseBoundary(
      ObjectProvider<JdbcBoundaryPostProcessor> boundary) {
    return new LiquibaseBoundaryPostProcessor(boundary);
  }
}
