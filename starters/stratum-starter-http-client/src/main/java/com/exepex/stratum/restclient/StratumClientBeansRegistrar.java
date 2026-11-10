package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Registers a {@code RestClient} bean and a {@code RestTemplate} bean for each configured client,
 * named {@code stratum.http-client.<client>.rest-client} and {@code .rest-template}, which a
 * service injects with {@code @Qualifier("<client>")}. They are never injected without that
 * qualifier, so a service's own unqualified {@code RestClient} or {@code RestTemplate} bean stays
 * the one it gets, and a service's own bean with that qualifier wins over Stratum's. The clients
 * come from {@link HttpClientProperties#resolve}, so a client set only in Java gets its beans too.
 */
@StratumInternal
@RequiredArgsConstructor
public class StratumClientBeansRegistrar implements ImportBeanDefinitionRegistrar {

  /** The end of the name of a client's {@code RestClient} bean. */
  private static final String REST_CLIENT = ".rest-client";

  /** The end of the name of a client's {@code RestTemplate} bean. */
  private static final String REST_TEMPLATE = ".rest-template";

  /** The application's environment, from which the clients are resolved. */
  private final Environment environment;

  /** The application's beans, which build each client's beans when they are first needed. */
  private final BeanFactory beanFactory;

  /**
   * Registers the two beans of each configured client.
   *
   * @param importingClassMetadata the auto-configuration that imports this registrar, not used
   * @param registry the application's bean definitions
   */
  @Override
  public void registerBeanDefinitions(
      AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    HttpClientProperties.resolve(environment)
        .clients()
        .keySet()
        .forEach(client -> register(registry, client));
  }

  /** Registers one client's {@code RestClient} and {@code RestTemplate} beans. */
  private void register(BeanDefinitionRegistry registry, String client) {
    var prefix = HttpClientConstants.PREFIX + "." + client;
    registry.registerBeanDefinition(
        prefix + REST_CLIENT, qualified(RestClient.class, client, () -> restClient(client)));
    registry.registerBeanDefinition(
        prefix + REST_TEMPLATE, qualified(RestTemplate.class, client, () -> restTemplate(client)));
  }

  /**
   * Returns a bean of the client's that only a qualifier with its name injects, and that gives way
   * to any other bean of the same type and qualifier.
   */
  private <T> RootBeanDefinition qualified(Class<T> type, String client, Supplier<T> supplier) {
    var definition = new RootBeanDefinition(type, supplier);
    definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, client));
    definition.setDefaultCandidate(false);
    definition.setFallback(true);
    return definition;
  }

  /** Returns the client's {@code RestClient}, the one {@link StratumHttpClients} gives. */
  private RestClient restClient(String client) {
    return beanFactory.getBean(StratumHttpClients.class).restClient(client);
  }

  /**
   * Builds the client's {@code RestTemplate} from Spring Boot's builder, or from a plain one when
   * there is none, as in a WebFlux service.
   */
  private RestTemplate restTemplate(String client) {
    var builder =
        beanFactory
            .getBeanProvider(RestTemplateBuilder.class)
            .getIfAvailable(RestTemplateBuilder::new);
    return beanFactory.getBean(StratumClientBuilders.class).restTemplate(client, builder);
  }
}
