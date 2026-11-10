package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.StratumClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;

/**
 * The interceptors of each client: every {@link GlobalClientInterceptor} bean, in {@code @Order}
 * order, then the beans its interfaces name in {@code @StratumClient(interceptors = ...)}. A named
 * bean that is also global runs once, as a global one. A named type with no bean, or two, stops
 * startup with Spring's own error. The beans are looked up on a client's first call, not while the
 * clients are built, so that an interceptor may use a client.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientInterceptors {

  /** The bean name of the interceptors of every client. */
  public static final String BEAN_NAME = "stratumClientInterceptors";

  /** The interceptor types each client's interfaces name, by client, each once. */
  private final Map<String, List<Class<? extends ClientInterceptor>>> namedTypes;

  /** The application's beans, which hold the interceptors. */
  private final ListableBeanFactory beans;

  /**
   * Returns the interceptors of every client, from the interfaces of each.
   *
   * @param interfaces each client's interfaces, by client
   * @param beans the application's beans
   * @return the interceptors
   */
  public static ClientInterceptors of(
      Map<String, List<Class<?>>> interfaces, ListableBeanFactory beans) {
    return new ClientInterceptors(
        interfaces.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, client -> namedTypesOf(client.getValue()))),
        beans);
  }

  /**
   * Returns whether a client has an interceptor, from the beans' definitions only, so that no
   * interceptor is created while the clients are built: an interceptor may itself need a client.
   *
   * @param client the client's name
   * @return whether any global interceptor exists or the client names one
   * @throws NoSuchBeanDefinitionException if a type the client names has no bean
   * @throws NoUniqueBeanDefinitionException if a type the client names has several beans
   */
  public boolean hasInterceptors(String client) {
    var named = namedTypes.getOrDefault(client, List.of());
    named.forEach(this::requireOneBean);
    return !named.isEmpty() || namesOf(GlobalClientInterceptor.class).length > 0;
  }

  /**
   * Returns one client's interceptors in the order they run: the global ones, then its own.
   *
   * @param client the client's name
   * @return the interceptors, empty when it has none
   */
  public List<ClientInterceptor> interceptorsOf(String client) {
    var named =
        namedTypes.getOrDefault(client, List.of()).stream()
            .<ClientInterceptor>map(beans::getBean)
            .filter(interceptor -> !(interceptor instanceof GlobalClientInterceptor));
    return Stream.concat(
            beans.getBeanProvider(GlobalClientInterceptor.class).orderedStream(), named)
        .toList();
  }

  /** Stops startup unless exactly one bean has the type, as Spring would on the first call. */
  private void requireOneBean(Class<? extends ClientInterceptor> type) {
    var names = namesOf(type);
    switch (names.length) {
      case 0 -> throw new NoSuchBeanDefinitionException(type);
      case 1 -> {
        // Exactly one bean: the type is fine.
      }
      default -> throw new NoUniqueBeanDefinitionException(type, names);
    }
  }

  /** Returns the names of the beans of a type, without creating any. */
  private String[] namesOf(Class<?> type) {
    return beans.getBeanNamesForType(type, true, false);
  }

  /** Returns the interceptor types a client's interfaces name, joined, each once. */
  private static List<Class<? extends ClientInterceptor>> namedTypesOf(List<Class<?>> interfaces) {
    return interfaces.stream()
        .map(type -> type.getAnnotation(StratumClient.class))
        .filter(Objects::nonNull)
        .flatMap(marked -> Arrays.stream(marked.interceptors()))
        .distinct()
        .toList();
  }
}
