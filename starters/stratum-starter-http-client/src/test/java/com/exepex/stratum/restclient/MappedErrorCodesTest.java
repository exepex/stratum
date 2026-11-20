package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.OperationSettings;
import com.exepex.stratum.restclient.testsupport.mapped.MappedTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpStatus;

@DisplayName("MappedErrorCodes gives an error status the service's own code, if one is set")
class MappedErrorCodesTest {

  /** The stock client's codes, merged over the shared defaults as the module merges them. */
  private final MappedErrorCodes codes =
      new MappedErrorCodes(
          HttpClientProperties.builder()
              .defaults(
                  ClientSettings.builder()
                      .errors(
                          Map.of(
                              HttpStatus.UNAUTHORIZED.value(), MappedTestData.UNAUTHORIZED_CODE,
                              HttpStatus.NOT_FOUND.value(), MappedTestData.SHARED_NOT_FOUND_CODE))
                      .build())
              .clients(
                  Map.of(
                      MappedTestData.STOCK,
                      ClientSettings.builder()
                          .errors(
                              Map.of(HttpStatus.NOT_FOUND.value(), MappedTestData.NOT_FOUND_CODE))
                          .operations(
                              Map.of(
                                  MappedTestData.LIST_ORDERS,
                                  OperationSettings.builder()
                                      .errors(
                                          Map.of(
                                              HttpStatus.NOT_FOUND.value(),
                                              MappedTestData.LIST_NOT_FOUND_CODE))
                                      .build()))
                          .build()))
              .build()
              .clientSettings(MappedTestData.STOCK));

  /** Returns the code a status gets for a call of the operation, or null when it gets none. */
  private String codeOf(HttpStatus status, String operation) {
    return codes.codeFor(status.value(), operation).map(ErrorCode::getCode).orElse(null);
  }

  @Nested
  @DisplayName("for a mapped status")
  class Mapped {

    @Test
    @DisplayName("an operation's code wins over the client's, which wins over the defaults'")
    void operationOverClientOverDefaults() {
      assertThat(codeOf(HttpStatus.NOT_FOUND, MappedTestData.LIST_ORDERS))
          .isEqualTo(MappedTestData.LIST_NOT_FOUND_CODE);
      assertThat(codeOf(HttpStatus.NOT_FOUND, null)).isEqualTo(MappedTestData.NOT_FOUND_CODE);
    }

    @Test
    @DisplayName("a status only the defaults map applies to the client and its operation")
    void defaultsOnlyStatusApplies() {
      assertThat(codeOf(HttpStatus.UNAUTHORIZED, null)).isEqualTo(MappedTestData.UNAUTHORIZED_CODE);
      assertThat(codeOf(HttpStatus.UNAUTHORIZED, MappedTestData.LIST_ORDERS))
          .isEqualTo(MappedTestData.UNAUTHORIZED_CODE);
    }

    @Test
    @DisplayName("an operation with no settings of its own takes the client's codes")
    void unknownOperationTakesClientCodes() {
      assertThat(codeOf(HttpStatus.NOT_FOUND, "show-order"))
          .isEqualTo(MappedTestData.NOT_FOUND_CODE);
    }

    @Test
    @DisplayName("the code is an error with the fixed description of SX-HTTPC-0007")
    void codeIsAnErrorWithFixedText() {
      var code = codes.codeFor(HttpStatus.NOT_FOUND.value(), null).orElseThrow();

      assertThat(code.getSeverity()).isEqualTo(Severity.ERROR);
      assertThat(code.getDescription())
          .isEqualTo(HttpClientErrorCode.DOWNSTREAM_ERROR.getDescription());
      assertThat(code.getFix()).isEmpty();
    }
  }

  @Nested
  @DisplayName("for a status no setting maps")
  class Unmapped {

    /** The first status above the error statuses. */
    private static final int ABOVE_ERRORS = 600;

    /** A status far above the error statuses. */
    private static final int FAR_ABOVE_ERRORS = 999;

    /** Statuses no setting can map: below and above the error statuses, and far outside them. */
    private static final List<Integer> UNMAPPABLE =
        List.of(HttpStatus.OK.value(), HttpStatus.FOUND.value(), ABOVE_ERRORS, FAR_ABOVE_ERRORS);

    @Test
    @DisplayName("an error status gets no code, so the answer keeps SX-HTTPC-0007 or 0009")
    void unlistedStatusHasNoCode() {
      assertThat(codes.codeFor(HttpStatus.CONFLICT.value(), MappedTestData.LIST_ORDERS)).isEmpty();
      assertThat(codes.codeFor(HttpStatus.SERVICE_UNAVAILABLE.value(), null)).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("UNMAPPABLE")
    @DisplayName("a status outside 400 to 599 that another service sends gets no code")
    void outOfRangeStatusHasNoCode(int status) {
      assertThat(codes.codeFor(status, MappedTestData.LIST_ORDERS)).isEmpty();
    }
  }
}
