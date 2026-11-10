package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.RequiredArgsConstructor;

/**
 * A client's own interceptor that records each attempt and signs it with the size of the body it
 * could read, or {@code none}.
 */
@RequiredArgsConstructor
public class SigningInterceptor implements ClientInterceptor {

  /** The name it records under. */
  public static final String NAME = "sign";

  /** Where the runs are recorded. */
  private final CallLog log;

  @Override
  public void beforeSend(OutgoingRequest request) {
    log.note(NAME, request);
    request.setHeader(
        HeadersTestData.SIGNATURE,
        request.body().map(body -> String.valueOf(body.length)).orElse("none"));
  }
}
