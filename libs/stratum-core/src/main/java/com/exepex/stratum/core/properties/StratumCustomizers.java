package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;

/**
 * Finds a service's Java configuration of a module: the customizer classes it lists in {@code
 * META-INF/spring.factories}. Every module reads them the same way, before any bean exists, so the
 * same customizer works whether the module needs its settings early, like logging, or later.
 */
@UtilityClass
public class StratumCustomizers {

  /**
   * Returns the module's customizers, in {@code @Order} order.
   *
   * @param customizerType the module's customizer interface, the key in {@code spring.factories}
   * @param <C> the customizer interface
   * @return a new instance of every listed customizer class
   */
  public static <C extends StratumPropertiesCustomizer<?>> List<C> load(Class<C> customizerType) {
    return SpringFactoriesLoader.forDefaultResourceLocation(ClassUtils.getDefaultClassLoader())
        .load(customizerType);
  }

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares any {@link
   * StratumPropertiesCustomizer} as a bean, typed or not. Modules read customizers from {@code
   * spring.factories} only, so the bean would be silently ignored.
   *
   * @param beanFactory the service's beans
   * @throws StratumException {@code SX-CORE-1004} naming the customizer beans
   */
  public static void rejectBeans(ListableBeanFactory beanFactory) {
    var customizerBeanNames = beanFactory.getBeanNamesForType(StratumPropertiesCustomizer.class);
    if (customizerBeanNames.length > 0) {
      throw new StratumException(
          CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN,
          "customizer beans " + String.join(", ", customizerBeanNames));
    }
  }
}
