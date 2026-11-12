package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;

/**
 * Creates the JDBC boundary around every data source bean, with the time limits of their
 * statements, unless {@code stratum.data.enabled=false}.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
public final class JdbcBoundaryAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a {@code
   * DataPropertiesCustomizer} as a bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public JdbcBoundaryAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Wraps every data source bean. A service replaces it with a bean of the same name, for example
   * to skip one of its data sources. Static, because Spring creates bean post-processors before the
   * other beans.
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
