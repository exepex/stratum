package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.StreamingHttpOutputMessage;

/**
 * Tells whether a request's body is streamed: written while it is sent, so it cannot be sent a
 * second time. Every part of the HTTP client starter that must not resend or read a body asks here.
 */
@StratumInternal
@UtilityClass
public class StreamedBody {

  /**
   * Returns whether the body is streamed: a {@link StreamingHttpOutputMessage.Body} or any {@link
   * Resource}, such as an {@code InputStreamResource}.
   *
   * @param body the request's body, or {@code null} for none
   * @return whether it is streamed
   */
  public static boolean isStreamed(@Nullable Object body) {
    return body instanceof StreamingHttpOutputMessage.Body || body instanceof Resource;
  }
}
