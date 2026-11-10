package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.RequiredArgsConstructor;

/**
 * A global interceptor that, on each call of the signed client, first calls the lone client, as an
 * interceptor that fetches a signing key through another Stratum client does. Ordered first, so
 * that a refusal set on the log is taken by the lone client's call.
 */
@RequiredArgsConstructor
public class NestingInterceptor implements GlobalClientInterceptor {

  /** The client the key is fetched through. */
  private final LoneClient keys;

  @Override
  public void beforeSend(OutgoingRequest request) {
    if (HeadersTestData.SIGNED.equals(request.client())) {
      keys.echo();
    }
  }
}
