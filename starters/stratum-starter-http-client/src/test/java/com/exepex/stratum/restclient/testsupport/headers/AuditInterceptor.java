package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;
import lombok.RequiredArgsConstructor;

/**
 * A global interceptor that records each attempt of every client, and refuses one when told to,
 * before anything is sent.
 */
@RequiredArgsConstructor
public class AuditInterceptor implements GlobalClientInterceptor {

  /** The name it records under. */
  public static final String NAME = "audit";

  /** Where the runs are recorded. */
  private final CallLog log;

  @Override
  public void beforeSend(OutgoingRequest request) {
    log.note(NAME, request);
    var refusal = log.takeRefusal();
    if (refusal != null) {
      throw refusal;
    }
  }
}
