package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A global interceptor that needs a Stratum client, as one that fetches a signing key through
 * another service would. It records each call it sees.
 */
@RequiredArgsConstructor
public class ClientUsingInterceptor implements GlobalClientInterceptor {

  /** The name it records under. */
  public static final String NAME = "client-user";

  /** The client it would fetch its key through. */
  @Getter private final PlainClient keys;

  /** Where the runs are recorded. */
  private final CallLog log;

  @Override
  public void beforeSend(OutgoingRequest request) {
    log.note(NAME, request);
  }
}
