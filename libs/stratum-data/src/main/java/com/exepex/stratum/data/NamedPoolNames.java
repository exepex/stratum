package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * Finds the named pools of an application: those under {@code stratum.data.sources}, and those of
 * the service's {@code <name>JdbcConnectionDetails} beans that are no default candidates.
 */
@StratumInternal
public final class NamedPoolNames {

  /** Tells a pool's name from any other bean name prefix. */
  private final PoolKeyCheck keyCheck = new PoolKeyCheck();

  /**
   * Returns the pools' names, in alphabetical order.
   *
   * @param registry the application's bean definitions
   * @param settings the module's settings
   * @return the names
   */
  public Set<String> names(BeanDefinitionRegistry registry, DataProperties settings) {
    var names = new TreeSet<>(settings.sources().keySet());
    Arrays.stream(registry.getBeanDefinitionNames())
        .filter(beanName -> beanName.endsWith(DataConstants.DETAILS_SUFFIX))
        .filter(beanName -> !isDefaultCandidate(registry, beanName))
        .map(beanName -> beanName.substring(0, beanName.length() - suffixLength()))
        .filter(keyCheck::isName)
        .forEach(names::add);
    return names;
  }

  /**
   * Returns whether a defined bean is a default candidate, which a definition of no Spring kind
   * always is.
   *
   * @param registry the application's bean definitions
   * @param beanName the bean's name
   * @return whether unqualified injection may pick it
   */
  public boolean isDefaultCandidate(BeanDefinitionRegistry registry, String beanName) {
    return !(registry.getBeanDefinition(beanName) instanceof AbstractBeanDefinition definition)
        || definition.isDefaultCandidate();
  }

  /** Returns the length of the connection details bean names' end. */
  private int suffixLength() {
    return DataConstants.DETAILS_SUFFIX.length();
  }
}
