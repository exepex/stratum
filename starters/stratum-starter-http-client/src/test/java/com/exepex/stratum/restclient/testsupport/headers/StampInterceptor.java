package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.RequiredArgsConstructor;

/**
 * A client's second interceptor: it records each attempt and sets the signature again, which must
 * replace the value the signing interceptor set before it.
 */
@RequiredArgsConstructor
public class StampInterceptor implements ClientInterceptor {

  /** The name it records under. */
  public static final String NAME = "stamp";

  /** Where the runs are recorded. */
  private final CallLog log;

  @Override
  public void beforeSend(OutgoingRequest request) {
    log.note(NAME, request);
    request.setHeader(HeadersTestData.SIGNATURE, NAME);
  }
}
