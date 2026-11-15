package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * Finds the named pools of an application: those under {@code stratum.data.sources}, and those of
 * the service's {@code <name>JdbcConnectionDetails} beans that are no default candidates.
 */
@StratumInternal
public final class NamedPoolNames {

  /** A pool's name: lower-case letters and digits, starting with a letter, at most 31. */
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{0,30}");

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
        .filter(this::isName)
        .forEach(names::add);
    return names;
  }

  /**
   * Returns whether a text follows the rule of a pool's name and is not {@code main}. It needs no
   * HikariCP class, so code that runs without HikariCP may call it.
   *
   * @param name the text
   * @return whether it can name a pool
   */
  public boolean isName(String name) {
    return NAME.matcher(name).matches() && !DataConstants.MAIN_POOL.equals(name);
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
