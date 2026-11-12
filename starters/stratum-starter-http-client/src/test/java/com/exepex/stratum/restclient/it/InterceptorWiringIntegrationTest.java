package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.ClientUsingInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.ClientUsingInterceptorBeans;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A global interceptor's bean needs the plain client, as a signer that fetches its key would. */
@SpringBootTest(classes = {HeadersApplication.class, ClientUsingInterceptorBeans.class})
@DisplayName("An interceptor may use a Stratum client: the application starts and it runs")
class InterceptorWiringIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  @Test
  @DisplayName("the interceptor holds its client and runs on a call of that client")
  void interceptorWithClientRuns(
      @Autowired ClientUsingInterceptor interceptor,
      @Autowired PlainClient plain,
      @Autowired CallLog log) {
    log.clear();

    plain.echo();

    assertThat(interceptor.getKeys()).isNotNull();
    assertThat(log.interceptors()).contains(ClientUsingInterceptor.NAME);
  }
}
