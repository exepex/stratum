package com.exepex.stratum.restclient.testsupport.trace;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;

/** A client of one of the service's own services, which names no interceptor. */
@StratumClient(TraceTestData.TRACED)
public interface TracedClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();

  /**
   * Calls the echo path with the team's own correlation ID.
   *
   * @param correlationId the ID
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echoWith(
      @RequestHeader(LoggingContextConstants.CORRELATION_ID_HEADER) String correlationId);
}
