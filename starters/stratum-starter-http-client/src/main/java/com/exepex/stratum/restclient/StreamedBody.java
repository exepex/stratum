package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.util.MultiValueMap;

/**
 * Tells whether a request's body is streamed: written while it is sent, so it cannot be sent a
 * second time. Every part of the HTTP client starter that must not resend or read a body asks here.
 */
@StratumInternal
@UtilityClass
public class StreamedBody {

  /**
   * Returns whether the body is streamed: a {@link StreamingHttpOutputMessage.Body}, any {@link
   * Resource}, such as an {@code InputStreamResource}, or a multipart body with such a part, bare
   * or in an {@link HttpEntity}.
   *
   * @param body the request's body, or {@code null} for none
   * @return whether it is streamed
   */
  public static boolean isStreamed(@Nullable Object body) {
    return switch (body) {
      case StreamingHttpOutputMessage.Body _, Resource _ -> true;
      case HttpEntity<?> part -> isStreamed(part.getBody());
      case MultiValueMap<?, ?> parts ->
          parts.values().stream().flatMap(List::stream).anyMatch(StreamedBody::isStreamed);
      case null, default -> false;
    };
  }
}
