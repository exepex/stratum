package com.exepex.stratum.restclient.testsupport.trace;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.ClientInterceptor;
import com.exepex.stratum.httpclient.OutgoingRequest;

/** A team's interceptor that sets a correlation ID and a W3C trace header on each call. */
public class TraceSettingInterceptor implements ClientInterceptor {

  /** The W3C trace header it sets. */
  public static final String TRACEPARENT = "traceparent";

  @Override
  public void beforeSend(OutgoingRequest request) {
    request.setHeader(
        LoggingContextConstants.CORRELATION_ID_HEADER, TraceTestData.TEAM_CORRELATION_ID);
    request.setHeader(TRACEPARENT, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
  }
}
