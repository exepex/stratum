package com.exepex.stratum.restclient.testsupport;

import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Calls made through a client's {@code RestClient} or {@code RestTemplate} bean, found by its
 * qualifier as a service injects it, so that each test runs the same call through both beans. The
 * lookup asks the application's bean factory: only it reads the qualifier of a bean registered in
 * code, as Stratum registers these beans.
 */
@UtilityClass
public class ClientBeanCalls {

  /** Both beans of a client, for the tests that run on each. */
  public static final List<ClientBean> BEANS = List.of(ClientBean.values());

  /** Where {@code @FieldSource} finds {@link #BEANS}. */
  public static final String BOTH_BEANS =
      "com.exepex.stratum.restclient.testsupport.ClientBeanCalls#BEANS";

  /** The two beans of a client. */
  public enum ClientBean {
    /** The client's {@code RestClient} bean. */
    REST_CLIENT,
    /** The client's {@code RestTemplate} bean. */
    REST_TEMPLATE
  }

  /**
   * Gets a path through one of a client's beans.
   *
   * @param beans the application's beans
   * @param bean which of the client's beans
   * @param client the client's name
   * @param path the path, relative to the client's address
   * @return the answer's body
   */
  public static @Nullable String get(
      ApplicationContext beans, ClientBean bean, String client, String path) {
    return switch (bean) {
      case REST_CLIENT -> restClient(beans, client).get().uri(path).retrieve().body(String.class);
      case REST_TEMPLATE -> restTemplate(beans, client).getForObject(path, String.class);
    };
  }

  /**
   * Gets a path through one of a client's beans and drops the answer's body unread.
   *
   * @param beans the application's beans
   * @param bean which of the client's beans
   * @param client the client's name
   * @param path the path, relative to the client's address
   * @return the answer's status
   */
  public static HttpStatusCode getWithoutBody(
      ApplicationContext beans, ClientBean bean, String client, String path) {
    return switch (bean) {
      case REST_CLIENT ->
          restClient(beans, client).get().uri(path).retrieve().toBodilessEntity().getStatusCode();
      case REST_TEMPLATE ->
          restTemplate(beans, client).getForEntity(path, Void.class).getStatusCode();
    };
  }

  /**
   * Puts to a path through one of a client's beans, with a body or without one.
   *
   * @param beans the application's beans
   * @param bean which of the client's beans
   * @param client the client's name
   * @param path the path, relative to the client's address
   * @param body the body, or {@code null} to send none
   */
  public static void put(
      ApplicationContext beans,
      ClientBean bean,
      String client,
      String path,
      @Nullable String body) {
    if (bean == ClientBean.REST_TEMPLATE) {
      restTemplate(beans, client).put(path, body);
    } else {
      putWithRestClient(restClient(beans, client), path, body);
    }
  }

  /**
   * Posts a body to a path through one of a client's beans.
   *
   * @param beans the application's beans
   * @param bean which of the client's beans
   * @param client the client's name
   * @param path the path, relative to the client's address
   * @param body the body
   * @return the answer's body
   */
  public static @Nullable String post(
      ApplicationContext beans, ClientBean bean, String client, String path, String body) {
    return switch (bean) {
      case REST_CLIENT ->
          restClient(beans, client).post().uri(path).body(body).retrieve().body(String.class);
      case REST_TEMPLATE -> restTemplate(beans, client).postForObject(path, body, String.class);
    };
  }

  /**
   * Returns a client's {@code RestClient} bean, as {@code @Qualifier("<client>")} injects it.
   *
   * @param beans the application's beans
   * @param client the client's name
   * @return the bean
   */
  public static RestClient restClient(ApplicationContext beans, String client) {
    return BeanFactoryAnnotationUtils.qualifiedBeanOfType(
        beans.getAutowireCapableBeanFactory(), RestClient.class, client);
  }

  /**
   * Returns a client's {@code RestTemplate} bean, as {@code @Qualifier("<client>")} injects it.
   *
   * @param beans the application's beans
   * @param client the client's name
   * @return the bean
   */
  public static RestTemplate restTemplate(ApplicationContext beans, String client) {
    return BeanFactoryAnnotationUtils.qualifiedBeanOfType(
        beans.getAutowireCapableBeanFactory(), RestTemplate.class, client);
  }

  /** Puts through a {@code RestClient}, with no body when none is given. */
  private static void putWithRestClient(RestClient restClient, String path, @Nullable String body) {
    var request = restClient.put().uri(path);
    if (body != null) {
      request.body(body);
    }
    request.retrieve().toBodilessEntity();
  }
}
