package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import jakarta.validation.Validation;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;

/**
 * Finds a service's Java configuration of a module, the customizer classes it lists in {@code
 * META-INF/spring.factories}, and applies it to the module's settings. Every module reads them the
 * same way, before any bean exists, so the same customizer works whether the module needs its
 * settings early, like logging, or later.
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
   * Returns a module's settings: bound from {@code prefix}, with Stratum's defaults for anything
   * unset, changed by every customizer the service lists, then validated. It makes its own
   * validator, because some modules need their settings before any bean exists.
   *
   * @param environment the application's environment
   * @param prefix the module's property prefix, such as {@code stratum.logging}
   * @param settingsType the module's settings record
   * @param customizerType the module's customizer interface, the key in {@code spring.factories}
   * @param <P> the module's settings type
   * @param <C> the module's customizer interface
   * @return the settings
   * @throws StratumException {@code SX-CORE-1003} if a setting breaks its rules
   */
  public static <P, C extends StratumPropertiesCustomizer<P>> P resolveSettings(
      Environment environment, String prefix, Class<P> settingsType, Class<C> customizerType) {
    var boundProperties = Binder.get(environment).bindOrCreate(prefix, settingsType);
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      return new StratumPropertiesResolver(validatorFactory.getValidator())
          .resolve(boundProperties, load(customizerType).stream());
    }
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
