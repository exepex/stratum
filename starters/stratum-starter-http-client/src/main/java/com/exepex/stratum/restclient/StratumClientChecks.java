package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.StratumClient;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.Environment;

/**
 * The startup checks of Stratum's clients. They read only the clients' interfaces and the
 * configuration, never the network, and name a client or a key, never a value.
 */
@StratumInternal
@UtilityClass
public class StratumClientChecks {

  /** The type every {@code Mono} and {@code Flux} is. */
  private static final String PUBLISHER = "org.reactivestreams.Publisher";

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
    Arrays.stream(clientInterface.getMethods())
        .filter(method -> isPublisher(method.getReturnType()))
        .findFirst()
        .ifPresent(
            method -> {
              throw new StratumException(
                  HttpClientErrorCode.REACTIVE_METHOD,
                  "method " + clientInterface.getName() + "." + method.getName());
            });
    return name;
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
}
