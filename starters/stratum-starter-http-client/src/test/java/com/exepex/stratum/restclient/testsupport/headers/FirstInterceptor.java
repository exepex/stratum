package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.RequiredArgsConstructor;

/**
 * A second global interceptor, ordered before the audit one though declared after it. It records
 * the ordered client's calls only, so that the other tests see the audit interceptor alone.
 */
@RequiredArgsConstructor
public class FirstInterceptor implements GlobalClientInterceptor {

  /** The name it records under. */
  public static final String NAME = "first";

  /** Where the runs are recorded. */
  private final CallLog log;

  @Override
  public void beforeSend(OutgoingRequest request) {
    if (HeadersTestData.ORDERED.equals(request.client())) {
      log.note(NAME, request);
    }
  }
}
