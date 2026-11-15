package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.util.ClassUtils;

/**
 * Finds the application's JPA repository interfaces in its bean definitions, with no class path
 * scan: Spring Data defines each repository as a {@link JpaRepositoryFactoryBean}, or a subclass,
 * whose first constructor argument is the interface. Other Spring Data repositories are left out.
 */
@StratumInternal
public final class JpaRepositoryDefinitions {

  /**
   * Returns the JPA repository interfaces.
   *
   * @param registry the application's bean definitions
   * @param classLoader the application's class loader
   * @return the interfaces
   */
  public List<Class<?>> find(BeanDefinitionRegistry registry, ClassLoader classLoader) {
    return Arrays.stream(registry.getBeanDefinitionNames())
        .map(registry::getBeanDefinition)
        .filter(definition -> isJpaRepository(definition, classLoader))
        .map(definition -> repositoryInterface(definition, classLoader))
        .filter(Objects::nonNull)
        .<Class<?>>map(Class.class::cast)
        .toList();
  }

  /** Returns whether a definition is a JPA repository's factory bean. */
  private boolean isJpaRepository(BeanDefinition definition, ClassLoader classLoader) {
    var className = definition.getBeanClassName();
    return className != null
        && ClassUtils.isPresent(className, classLoader)
        && JpaRepositoryFactoryBean.class.isAssignableFrom(
            ClassUtils.resolveClassName(className, classLoader));
  }

  /** Returns the interface of a repository's factory bean, or none if it holds no interface. */
  private @Nullable Class<?> repositoryInterface(BeanDefinition definition, ClassLoader loader) {
    var argument = definition.getConstructorArgumentValues().getIndexedArgumentValue(0, null);
    var value = argument == null ? null : argument.getValue();
    return switch (value) {
      case Class<?> type -> type;
      case String name -> ClassUtils.resolveClassName(name, loader);
      case null, default -> null;
    };
  }
}
