package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The defaults copy {@code X-Tenant} and the plain client names it again as {@code x-tenant}. Each
 * test binds the request the service answers as Spring MVC does, and unbinds it after.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HttpClientConstants.DEFAULTS + ".copy-headers=" + HeadersTestData.TENANT,
      HeadersTestData.PLAIN_SETTINGS + ".copy-headers=x-tenant"
    })
@DisplayName("A client copies the named headers of the request the service answers, and only those")
class CopyHeadersIntegrationTest {

  /** The caller's tenant in most tests. */
  private static final String TENANT_VALUE = "acme";

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private PlainClient plain;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets the request the test bound, if any. */
  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** Returns the copied header's lines in the last call the server got. */
  private List<String> tenantSent() {
    return DOWNSTREAM.last().values(HeadersTestData.TENANT);
  }

  @Nested
  @DisplayName("while the service answers a request")
  class InRequest {

    @Test
    @DisplayName("the caller's header goes on the call, named once though both lists name it")
    void copiesCallerHeader() {
      HeadersTestData.answering(HeadersTestData.TENANT, TENANT_VALUE);

      plain.echo();

      assertThat(tenantSent()).containsExactly(TENANT_VALUE);
    }

    @Test
    @DisplayName("a header the request does not have is not sent")
    void absentHeaderNotSent() {
      HeadersTestData.answering("X-Other", TENANT_VALUE);

      plain.echo();

      assertThat(tenantSent()).isEmpty();
    }

    @Test
    @DisplayName("an empty value is not sent")
    void emptyValueNotSent() {
      HeadersTestData.answering(HeadersTestData.TENANT, "");

      plain.echo();

      assertThat(tenantSent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("with no request to copy from")
  class NoRequest {

    @Test
    @DisplayName("outside a request, as in a job after a request was served, nothing is copied")
    void outsideRequest() {
      HeadersTestData.answering(HeadersTestData.TENANT, TENANT_VALUE);
      plain.echo();
      RequestContextHolder.resetRequestAttributes();

      assertThat(plain.echo()).isEqualTo(EchoDownstream.BODY);

      assertThat(tenantSent()).isEmpty();
    }

    @Test
    @DisplayName("on another thread, as after @Async, nothing of the caller's request is copied")
    void otherThread() {
      HeadersTestData.answering(HeadersTestData.TENANT, TENANT_VALUE);
      plain.echo();

      assertThat(CompletableFuture.supplyAsync(plain::echo).join()).isEqualTo(EchoDownstream.BODY);

      assertThat(tenantSent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("with values a caller controls")
  class Hostile {

    /** How many values of one header the caller sends. */
    private static final int VALUE_COUNT = 50;

    /** Fifty values of one header, each different, in order. */
    private static final String[] MANY_VALUES =
        IntStream.rangeClosed(1, VALUE_COUNT)
            .mapToObj(number -> "tenant-" + number)
            .toArray(String[]::new);

    /** A value of 8 KB. */
    private static final String LONG_VALUE = "t".repeat(8 * 1024);

    @Test
    @DisplayName("fifty values go on as fifty lines, in the same order, never joined")
    void manyValuesInOrder() {
      HeadersTestData.answering(HeadersTestData.TENANT, MANY_VALUES);

      plain.echo();

      assertThat(tenantSent()).containsExactly(MANY_VALUES);
    }

    @Test
    @DisplayName("a value of 8 KB goes on whole")
    void longValue() {
      HeadersTestData.answering(HeadersTestData.TENANT, LONG_VALUE);

      plain.echo();

      assertThat(tenantSent()).containsExactly(LONG_VALUE);
    }

    @Test
    @DisplayName("a line break in a value opens no new header (a guard on the transport)")
    void lineBreakOpensNoHeader() {
      HeadersTestData.answering(HeadersTestData.TENANT, "acme\r\nX-Injected: yes");

      plain.echo();

      assertThat(DOWNSTREAM.last().values("X-Injected")).isEmpty();
      assertThat(tenantSent()).hasSize(1);
    }
  }
}
