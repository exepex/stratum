package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * Creates the JDBC boundary around every data source bean, unless {@code
 * stratum.data.enabled=false}.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
public final class JdbcBoundaryAutoConfiguration {

  /**
   * Wraps every data source bean. A service replaces it with a bean of the same name, for example
   * to skip one of its data sources.
   *
   * @return the post-processor, which wraps every data source bean
   */
  @Bean(DataConstants.JDBC_BOUNDARY)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.JDBC_BOUNDARY)
  public static JdbcBoundaryPostProcessor stratumJdbcBoundary() {
    return new JdbcBoundaryPostProcessor(beanName -> true);
  }
}
