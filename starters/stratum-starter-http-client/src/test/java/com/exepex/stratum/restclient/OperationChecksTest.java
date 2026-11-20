package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.operations.BadNameOperations;
import com.exepex.stratum.restclient.testsupport.operations.ListedOperations;
import com.exepex.stratum.restclient.testsupport.operations.TwiceNamedOperations;
import com.exepex.stratum.test.ErrorCodeChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName(
    "A client's operations stop startup when a name is wrong, used twice or carried by none")
class OperationChecksTest {

  /** The partner client's operations. */
  private static final String PARTNER_OPERATIONS =
      RestClientTestData.PARTNER_SETTINGS + ".operations";

  /** A code for a 404 of the list-orders operation. */
  private static final String LIST_ORDERS_NOT_FOUND = ".list-orders.errors[404]=ORD-0404";

  /** The partner client with its address and nothing scanned. */
  private final ApplicationContextRunner partner =
      RestClientTestData.contextRunner()
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.PARTNER_URL, RestClientTestData.UNUSED_URL));

  /** Returns the partner client joined to one generated interface. */
  private ApplicationContextRunner partnerWith(Class<?> generated) {
    return partner.withPropertyValues(
        RestClientTestData.setting(RestClientTestData.PARTNER_INTERFACE, generated.getName()));
  }

  @Nested
  @DisplayName("an operation a method carries")
  class Carried {

    @Test
    @DisplayName("with settings for it, the application starts")
    void configuredOperationStarts() {
      partnerWith(ListedOperations.class)
          .withPropertyValues(PARTNER_OPERATIONS + LIST_ORDERS_NOT_FOUND)
          .run(context -> assertThat(context).hasSingleBean(ListedOperations.class));
    }

    @Test
    @DisplayName("a name that is not a valid key part: SX-HTTPC-1009, naming the method")
    void badNameStopsStartup() {
      partnerWith(BadNameOperations.class)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                              context, HttpClientErrorCode.OPERATION_NAME_INVALID))
                      .hasMessageContaining("BadNameOperations.listOrders"));
    }

    @Test
    @DisplayName("a name two methods of one client carry: SX-HTTPC-1009, naming the second")
    void nameUsedTwiceStopsStartup() {
      partnerWith(TwiceNamedOperations.class)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                              context, HttpClientErrorCode.OPERATION_NAME_INVALID))
                      .hasMessageContaining("TwiceNamedOperations.listOrders"));
    }
  }

  @Nested
  @DisplayName("an operation the settings name")
  class Configured {

    @Test
    @DisplayName("that no method of the client carries: SX-HTTPC-1010, naming the key")
    void unknownOperationStopsStartup() {
      partnerWith(ListedOperations.class)
          .withPropertyValues(PARTNER_OPERATIONS + ".list-order.errors[404]=ORD-0404")
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                              context, HttpClientErrorCode.OPERATION_UNKNOWN))
                      .hasMessageContaining(PARTNER_OPERATIONS + ".list-order"));
    }

    @Test
    @DisplayName("of a client with no interface at all: SX-HTTPC-1010, naming the key")
    void clientWithoutInterfaceStopsStartup() {
      partner
          .withPropertyValues(PARTNER_OPERATIONS + LIST_ORDERS_NOT_FOUND)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                              context, HttpClientErrorCode.OPERATION_UNKNOWN))
                      .hasMessageContaining(PARTNER_OPERATIONS + ".list-orders"));
    }

    @Test
    @DisplayName("under defaults, where no operation belongs: SX-HTTPC-1010, naming the key")
    void defaultsOperationsStopStartup() {
      partnerWith(ListedOperations.class)
          .withPropertyValues(HttpClientConstants.DEFAULTS + ".operations" + LIST_ORDERS_NOT_FOUND)
          .run(
              context ->
                  assertThat(
                          ErrorCodeChecks.startupFailureOf(
                              context, HttpClientErrorCode.OPERATION_UNKNOWN))
                      .hasMessageContaining(HttpClientConstants.DEFAULTS + ".operations"));
    }
  }
}
