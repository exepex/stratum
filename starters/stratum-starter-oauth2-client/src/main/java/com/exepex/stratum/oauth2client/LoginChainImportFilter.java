package com.exepex.stratum.oauth2client;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.util.ClassUtils;

/**
 * Leaves out Spring Boot's OAuth2 client login chain while Boot's security set-up, {@code
 * spring-boot-security}, is absent, as this starter leaves it out. That chain signs every request
 * in through the identity provider and needs the {@code HttpSecurity} that only Boot's security
 * set-up turns on, so a servlet service would not start, and calling another service never needs a
 * login. With Boot's security set-up present, as when the service adds Spring Security itself,
 * nothing is left out.
 */
@StratumInternal
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class LoginChainImportFilter implements AutoConfigurationImportFilter, BeanClassLoaderAware {

  /** Spring Boot's servlet login chain for OAuth2 clients. */
  static final String LOGIN_CHAIN =
      "org.springframework.boot.security.oauth2.client.autoconfigure.servlet"
          + ".OAuth2ClientWebSecurityAutoConfiguration";

  /** A class of Boot's security set-up, present only with {@code spring-boot-security}. */
  static final String BOOT_SECURITY =
      "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration";

  /** The class whose presence means Boot's security set-up is there; tests give another. */
  private final String bootSecurity;

  /** The class loader of the application, which the set-up is looked for in. */
  private @Nullable ClassLoader classLoader;

  /** Looks for Boot's security set-up, as Spring makes the filter. */
  public LoginChainImportFilter() {
    this(BOOT_SECURITY);
  }

  /**
   * Keeps the application's class loader.
   *
   * @param classLoader the class loader
   */
  @Override
  public void setBeanClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Returns, for each auto-configuration, whether it is kept: all are, except the login chain while
   * Boot's security set-up is absent.
   *
   * @param autoConfigurationClasses the auto-configurations, some entries possibly null
   * @param autoConfigurationMetadata what is known of them before they load
   * @return whether each is kept, in the same order
   */
  @Override
  public boolean[] match(
      @Nullable String[] autoConfigurationClasses,
      AutoConfigurationMetadata autoConfigurationMetadata) {
    var withBootSecurity = ClassUtils.isPresent(bootSecurity, classLoader);
    var kept = new boolean[autoConfigurationClasses.length];
    // An index loop, as the answer is an array of primitives in the same order.
    for (var index = 0; index < kept.length; index++) {
      kept[index] = withBootSecurity || !LOGIN_CHAIN.equals(autoConfigurationClasses[index]);
    }
    return kept;
  }
}
