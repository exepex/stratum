package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.httpclient.StratumOperation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * The startup checks of Stratum's clients. They read only the clients' interfaces and the
 * configuration, never the network, and name a client or a key, never a value.
 */
@StratumInternal
@UtilityClass
public class StratumClientChecks {

  /** The type every {@code Mono} and {@code Flux} is. */
  private static final String PUBLISHER = "org.reactivestreams.Publisher";

  /** A type of Spring Security's OAuth2 client, which fetches the service's own tokens. */
  private static final String OAUTH2_CLIENT =
      "org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager";

  /** The type of a caller's token that Spring Security's resource server has verified. */
  private static final String VERIFIED_TOKEN =
      "org.springframework.security.oauth2.server.resource.authentication"
          + ".AbstractOAuth2TokenAuthenticationToken";

  /**
   * Returns the name of a client interface, after checking the name and the methods.
   *
   * @param clientInterface an interface with {@code @StratumClient}
   * @return the client's name
   * @throws StratumException {@code SX-HTTPC-1003} if the name is not lowercase letters, digits and
   *     hyphens from a letter, or {@code SX-HTTPC-1002} if a method returns {@code Mono} or {@code
   *     Flux}
   */
  public static String checkInterface(Class<?> clientInterface) {
    var name = clientInterface.getAnnotation(StratumClient.class).value();
    if (!HttpClientConstants.CLIENT_NAME.matcher(name).matches()) {
      throw new StratumException(
          HttpClientErrorCode.CLIENT_NAME_INVALID, "interface " + clientInterface.getName());
    }
    checkMethods(clientInterface);
    return name;
  }

  /**
   * Returns an interface that a client's {@code interfaces} setting names, after checking it.
   *
   * @param client the client's name
   * @param interfaceName the interface's full name, as set
   * @param classLoader the class loader of the application's beans
   * @return the interface
   * @throws StratumException {@code SX-HTTPC-1007} if no interface of that name is on the
   *     classpath, {@code SX-HTTPC-1008} if it is marked {@code @StratumClient} for another client,
   *     or {@code SX-HTTPC-1002} if a method returns {@code Mono} or {@code Flux}
   */
  public static Class<?> namedInterface(
      String client, String interfaceName, @Nullable ClassLoader classLoader) {
    var setting = HttpClientConstants.CLIENTS + "." + client + ".interfaces";
    if (!ClassUtils.isPresent(interfaceName, classLoader)) {
      throw new StratumException(
          HttpClientErrorCode.INTERFACE_NOT_FOUND, setting + " names " + interfaceName);
    }
    var namedInterface = ClassUtils.resolveClassName(interfaceName, classLoader);
    if (!namedInterface.isInterface()) {
      throw new StratumException(
          HttpClientErrorCode.INTERFACE_NOT_FOUND, setting + " names class " + interfaceName);
    }
    var marked = namedInterface.getAnnotation(StratumClient.class);
    if (marked != null && !marked.value().equals(client)) {
      throw new StratumException(
          HttpClientErrorCode.INTERFACE_TWO_CLIENTS,
          "interface "
              + interfaceName
              + " is marked for client "
              + marked.value()
              + " and named in "
              + setting);
    }
    checkMethods(namedInterface);
    return namedInterface;
  }

  /** Stops startup when a method of the interface returns a Reactive Streams publisher. */
  private static void checkMethods(Class<?> clientInterface) {
    Arrays.stream(clientInterface.getMethods())
        .filter(method -> isPublisher(method.getReturnType()))
        .findFirst()
        .ifPresent(method -> rejectReactiveMethod(clientInterface, method));
  }

  /** Stops startup for a client method that returns a Reactive Streams publisher. */
  private static void rejectReactiveMethod(Class<?> clientInterface, Method method) {
    throw new StratumException(
        HttpClientErrorCode.REACTIVE_METHOD,
        "method " + clientInterface.getName() + "." + method.getName());
  }

  /**
   * Returns whether the type is a Reactive Streams publisher, such as {@code Mono} or {@code Flux},
   * by name, so that Reactor need not be present.
   */
  private static boolean isPublisher(Class<?> type) {
    return PUBLISHER.equals(type.getName())
        || Stream.concat(
                Stream.ofNullable(type.getSuperclass()), Arrays.stream(type.getInterfaces()))
            .anyMatch(StratumClientChecks::isPublisher);
  }

  /**
   * Checks the operations of every client: each {@code @StratumOperation} name on its interfaces,
   * and each operation its settings name.
   *
   * @param interfacesByClient the interfaces of each client
   * @param settings the settings of every client
   * @throws StratumException {@code SX-HTTPC-1009} if a name is not lowercase letters, digits and
   *     hyphens, or two methods of one client carry it, or {@code SX-HTTPC-1010} if a client's
   *     {@code operations} names one that no method of its interfaces carries, or {@code
   *     operations} is set under {@code defaults}
   */
  public static void checkOperations(
      Map<String, List<Class<?>>> interfacesByClient, HttpClientProperties settings) {
    if (!settings.defaults().operations().isEmpty()) {
      throw new StratumException(
          HttpClientErrorCode.OPERATION_UNKNOWN, HttpClientConstants.DEFAULTS + ".operations");
    }
    var operationsByClient = new HashMap<String, Set<String>>();
    interfacesByClient.forEach(
        (client, interfaces) -> operationsByClient.put(client, operationsOf(interfaces)));
    settings
        .clients()
        .forEach(
            (client, own) ->
                checkConfiguredOperations(
                    client, own, operationsByClient.getOrDefault(client, Set.of())));
  }

  /** Returns the operation names that the methods of one client's interfaces carry, once each. */
  private static Set<String> operationsOf(List<Class<?>> interfaces) {
    var names = new HashSet<String>();
    interfaces.stream()
        .flatMap(clientInterface -> Arrays.stream(clientInterface.getMethods()))
        .distinct()
        .filter(method -> method.isAnnotationPresent(StratumOperation.class))
        .forEach(method -> addOperation(method, names));
    return names;
  }

  /** Adds a method's operation name, and stops startup when it is not valid or already taken. */
  private static void addOperation(Method method, Set<String> names) {
    var name = method.getAnnotation(StratumOperation.class).value();
    if (!HttpClientConstants.OPERATION_NAME.matcher(name).matches() || !names.add(name)) {
      throw new StratumException(
          HttpClientErrorCode.OPERATION_NAME_INVALID,
          "method " + method.getDeclaringClass().getName() + "." + method.getName());
    }
  }

  /** Stops startup when a client's settings name an operation that none of its methods carries. */
  private static void checkConfiguredOperations(
      String client, ClientSettings own, Set<String> operations) {
    own.operations().keySet().stream()
        .filter(name -> !operations.contains(name))
        .findFirst()
        .ifPresent(name -> rejectUnknownOperation(client, name));
  }

  /** Stops startup for an operation that a client's settings name and none of its methods carry. */
  private static void rejectUnknownOperation(String client, String operation) {
    throw new StratumException(
        HttpClientErrorCode.OPERATION_UNKNOWN,
        HttpClientConstants.CLIENTS + "." + client + ".operations." + operation);
  }

  /**
   * Checks that no Spring Boot setting of an HTTP service group is set for a client, since Stratum
   * builds the client and would ignore it.
   *
   * @param environment the application's environment
   * @param client the client's name
   * @throws StratumException {@code SX-HTTPC-1001} naming the Stratum key to use instead
   */
  public static void checkNoBootSettings(Environment environment, String client) {
    var bootGroup =
        ConfigurationPropertyName.of(HttpClientConstants.BOOT_SERVICE_CLIENTS + "." + client);
    var bootGroupSet =
        StreamSupport.stream(ConfigurationPropertySources.get(environment).spliterator(), false)
            .anyMatch(
                source ->
                    source.containsDescendantOf(bootGroup) == ConfigurationPropertyState.PRESENT);
    if (bootGroupSet) {
      throw new StratumException(
          HttpClientErrorCode.BOOT_CLIENT_SETTING,
          bootGroup + ".* is set; use " + HttpClientConstants.CLIENTS + "." + client + ".*");
    }
  }

  /**
   * Returns a client's address.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the address
   * @throws StratumException {@code SX-HTTPC-1004} naming the key to set
   */
  public static String requireUrl(String client, ClientSettings settings) {
    var url = settings.url();
    if (url == null || url.isBlank()) {
      throw new StratumException(
          HttpClientErrorCode.URL_MISSING, HttpClientConstants.CLIENTS + "." + client + ".url");
    }
    return url;
  }

  /**
   * Checks that the Spring Security library each client's kind of auth needs is present.
   *
   * @param client the client's name
   * @param auth the client's auth settings
   * @param classLoader the class loader of the application's beans
   * @throws StratumException {@code SX-HTTPC-1006} naming the client and the library to add
   */
  public static void checkAuthLibrary(
      String client, ClientAuth auth, @Nullable ClassLoader classLoader) {
    if (auth.clientCredentials() != null && !ClassUtils.isPresent(OAUTH2_CLIENT, classLoader)) {
      throw authLibraryMissing(client);
    }
    if (auth.isTokenRelay() && !ClassUtils.isPresent(VERIFIED_TOKEN, classLoader)) {
      throw authLibraryMissing(client);
    }
  }

  /**
   * Returns the failure of a client whose kind of auth has no Spring Security library to send it.
   *
   * @param client the client's name
   * @return {@code SX-HTTPC-1006} naming the client
   */
  public static StratumException authLibraryMissing(String client) {
    return new StratumException(
        HttpClientErrorCode.AUTH_LIBRARY_MISSING,
        HttpClientConstants.CLIENTS + "." + client + ".auth");
  }
}
