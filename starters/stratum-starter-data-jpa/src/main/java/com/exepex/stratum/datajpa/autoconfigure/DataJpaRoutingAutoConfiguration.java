package com.exepex.stratum.datajpa.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.datajpa.DataJpaConstants;
import com.exepex.stratum.datajpa.routing.OpenInViewCheck;
import com.exepex.stratum.datajpa.routing.RoutingRegistrar;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Puts JPA repositories on named pools when {@code stratum.data.sources.<name>.packages} or
 * {@code @UseDataSource} maps any, unless {@code stratum.data.enabled=false}.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
public final class DataJpaRoutingAutoConfiguration {

  /**
   * Maps the repositories and registers the routing beans if any is mapped. A service replaces it
   * with a bean of the same name. Static, because Spring creates registry post-processors before
   * the other beans.
   *
   * @return the registrar
   */
  @Bean(DataJpaConstants.ROUTING_REGISTRAR)
  @Fallback
  @ConditionalOnMissingBean(name = DataJpaConstants.ROUTING_REGISTRAR)
  public static RoutingRegistrar stratumRoutingRegistrar() {
    return new RoutingRegistrar();
  }

  /**
   * Stops a servlet web application whose repositories are mapped while open-in-view is on, which
   * only a servlet web application has. A service replaces it with a bean of the same name.
   *
   * @param beanFactory the bean factory, which shows whether repositories are mapped
   * @param environment the application's environment
   * @return the check
   */
  @Bean(DataJpaConstants.OPEN_IN_VIEW_CHECK)
  @Fallback
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnMissingBean(name = DataJpaConstants.OPEN_IN_VIEW_CHECK)
  public OpenInViewCheck stratumOpenInViewCheck(BeanFactory beanFactory, Environment environment) {
    return new OpenInViewCheck(beanFactory, environment);
  }
}
