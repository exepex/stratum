package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.Arrays;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Names and settings shared by the tests of headers and interceptors. */
@UtilityClass
public class HeadersTestData {

  /** The client whose interfaces name interceptors. */
  public static final String SIGNED = "signed";

  /** The client with no interceptor of its own. */
  public static final String PLAIN = "plain";

  /** The client that names only a global interceptor. */
  public static final String LONE = "lone";

  /** The client that names two interceptors of its own. */
  public static final String ORDERED = "ordered";

  /** The prefix of the signed client's settings. */
  public static final String SIGNED_SETTINGS = HttpClientConstants.CLIENTS + "." + SIGNED;

  /** The prefix of the plain client's settings. */
  public static final String PLAIN_SETTINGS = HttpClientConstants.CLIENTS + "." + PLAIN;

  /** The end of each client's address key. */
  private static final String URL = ".url";

  /** The header the tests copy from the request the service answers. */
  public static final String TENANT = "X-Tenant";

  /** The header a fixed setting sends. */
  public static final String PARTNER_ID = "X-Partner-Id";

  /** The header the signing interceptor sets. */
  public static final String SIGNATURE = "X-Signature";

  /**
   * Binds a request the service is answering to the current thread, with the headers given, as
   * Spring MVC's {@code FrameworkServlet} binds each request it handles. The caller resets it with
   * {@code RequestContextHolder.resetRequestAttributes()}.
   *
   * @param name a header's name
   * @param values its values, one header line each, in order
   */
  public static void answering(String name, String... values) {
    var request = new MockHttpServletRequest();
    Arrays.stream(values).forEach(value -> request.addHeader(name, value));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  /**
   * Points the signed, plain, lone and ordered clients, and the partner client, at one server.
   *
   * @param registry the test's properties
   * @param url the server's address
   */
  public static void pointClientsAt(DynamicPropertyRegistry registry, Supplier<Object> url) {
    registry.add(SIGNED_SETTINGS + URL, url);
    registry.add(PLAIN_SETTINGS + URL, url);
    registry.add(HttpClientConstants.CLIENTS + "." + LONE + URL, url);
    registry.add(HttpClientConstants.CLIENTS + "." + ORDERED + URL, url);
    registry.add(HttpClientConstants.CLIENTS + ".partner.url", url);
  }
}
