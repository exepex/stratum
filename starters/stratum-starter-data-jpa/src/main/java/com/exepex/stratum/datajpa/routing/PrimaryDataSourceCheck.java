package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * Checks, when repositories are mapped, that the routing data source can be the application's only
 * primary {@link DataSource} (ADR 0022): Spring Boot defines {@code dataSource}, no other data
 * source is primary, and no bean already has the routing data source's name.
 */
@StratumInternal
public final class PrimaryDataSourceCheck {

  /** The package of Spring Boot's own configuration, whose {@code dataSource} routing wraps. */
  private static final String BOOT_PACKAGE = "org.springframework.boot.";

  /**
   * Checks the application's data source definitions.
   *
   * @param registry the application's bean definitions
   * @param beanFactory the same bean factory, which predicts each bean's type
   * @throws StratumException {@code SX-DATA-1007} if routing cannot be the only primary data source
   */
  public void check(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory) {
    if (registry.containsBeanDefinition(DataConstants.ROUTING_BEAN)) {
      throw invalid("the service defines a bean named " + DataConstants.ROUTING_BEAN);
    }
    if (!isBootDataSource(registry)) {
      throw invalid("the bean " + DataConstants.MAIN_BEAN + " is not Spring Boot's");
    }
    Arrays.stream(beanFactory.getBeanNamesForType(DataSource.class, true, false))
        .filter(name -> registry.containsBeanDefinition(name))
        .filter(name -> registry.getBeanDefinition(name).isPrimary())
        .findFirst()
        .ifPresent(this::rejectPrimary);
  }

  /** Returns whether Spring Boot's configuration defines {@code dataSource}. */
  private boolean isBootDataSource(BeanDefinitionRegistry registry) {
    if (!registry.containsBeanDefinition(DataConstants.MAIN_BEAN)) {
      return false;
    }
    var factoryMethod =
        registry.getBeanDefinition(DataConstants.MAIN_BEAN)
                instanceof AnnotatedBeanDefinition definition
            ? definition.getFactoryMethodMetadata()
            : null;
    return factoryMethod != null && factoryMethod.getDeclaringClassName().startsWith(BOOT_PACKAGE);
  }

  /** Fails because another data source is primary. */
  private void rejectPrimary(String beanName) {
    throw invalid("the bean %s is another primary DataSource".formatted(beanName));
  }

  /** Returns the startup failure, which says that repositories are mapped. */
  private StratumException invalid(String reason) {
    return new StratumException(
        DataErrorCode.ROUTING_INVALID, "repositories are mapped to named pools, but " + reason);
  }
}
