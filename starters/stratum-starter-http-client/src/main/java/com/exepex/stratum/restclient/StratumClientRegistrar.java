package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.StratumClient;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.service.registry.AbstractHttpServiceRegistrar;

/**
 * Finds the {@code @StratumClient} interfaces in the application's packages, the ones Spring Boot
 * scans for entities and repositories, and makes each client name one HTTP service group. Two
 * interfaces with the same name join one group. Each interface is checked as it is found ({@link
 * StratumClientChecks#checkInterface}).
 */
@StratumInternal
public class StratumClientRegistrar extends AbstractHttpServiceRegistrar {

  /** The application's beans, which hold its packages; set by Spring before registration. */
  private @Nullable BeanFactory beanFactory;

  /** The application's environment, for the scan; set by Spring before registration. */
  private @Nullable Environment environment;

  /** The class loader of the application's beans; set by Spring before registration. */
  private @Nullable ClassLoader beanClassLoader;

  /**
   * Keeps the application's beans, which the parent class does not share.
   *
   * @param beanFactory the application's beans
   * @throws BeansException if the parent class cannot use them
   */
  @Override
  public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
    super.setBeanFactory(beanFactory);
    this.beanFactory = beanFactory;
  }

  /**
   * Keeps the application's environment, which the parent class does not share.
   *
   * @param environment the application's environment
   */
  @Override
  public void setEnvironment(Environment environment) {
    super.setEnvironment(environment);
    this.environment = environment;
  }

  /**
   * Keeps the class loader of the application's beans, which the parent class does not share.
   *
   * @param beanClassLoader the class loader
   */
  @Override
  public void setBeanClassLoader(ClassLoader beanClassLoader) {
    super.setBeanClassLoader(beanClassLoader);
    this.beanClassLoader = beanClassLoader;
  }

  /**
   * Registers one group per client name with its interfaces.
   *
   * @param registry Spring's registry of HTTP service groups
   * @param importingClassMetadata the auto-configuration that imports this registrar, not used
   */
  @Override
  protected void registerHttpServices(
      GroupRegistry registry, AnnotationMetadata importingClassMetadata) {
    var factory = Objects.requireNonNull(beanFactory);
    if (!AutoConfigurationPackages.has(factory)) {
      return;
    }
    var scanner = new InterfaceScanner(Objects.requireNonNull(environment));
    AutoConfigurationPackages.get(factory).stream()
        .flatMap(basePackage -> scanner.findCandidateComponents(basePackage).stream())
        .map(candidate -> Objects.requireNonNull(candidate.getBeanClassName()))
        .distinct()
        .<Class<?>>map(className -> ClassUtils.resolveClassName(className, beanClassLoader))
        .collect(Collectors.groupingBy(StratumClientChecks::checkInterface))
        .forEach((client, interfaces) -> register(registry, client, interfaces));
  }

  /** Registers the interfaces of one client in its group. */
  private void register(GroupRegistry registry, String client, List<Class<?>> interfaces) {
    registry.forGroup(client).register(interfaces.toArray(Class<?>[]::new));
  }

  /** A scanner that finds interfaces with {@code @StratumClient}, which Spring's skips. */
  private static final class InterfaceScanner extends ClassPathScanningCandidateComponentProvider {

    /** Makes the scanner, with no filter but {@code @StratumClient}. */
    InterfaceScanner(Environment environment) {
      super(false, environment);
      addIncludeFilter(new AnnotationTypeFilter(StratumClient.class, false, true));
    }

    /** Accepts interfaces only, the type a client is. */
    @Override
    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
      return beanDefinition.getMetadata().isInterface();
    }
  }
}
