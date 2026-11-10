package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Marks each call of a client's interface whose {@code @RequestBody} argument is not streamed, so
 * that an interceptor may read that body ({@link StreamedBody}). A file, an {@code InputStream} or
 * a multipart body is never marked, and so never read. Added only to the clients that have
 * interceptors.
 */
final class StreamedBodyMarker implements HttpRequestValues.Processor {

  /** The position of each method's {@code @RequestBody} parameter, or -1 for none; found once. */
  private final Map<Method, Integer> bodyPositions = new ConcurrentHashMap<>();

  @Override
  public void process(
      Method method,
      MethodParameter[] parameters,
      @Nullable Object[] arguments,
      HttpRequestValues.Builder values) {
    int position = bodyPositions.computeIfAbsent(method, _ -> bodyPositionOf(parameters));
    if (position >= 0 && arguments != null && !StreamedBody.isStreamed(arguments[position])) {
      values.addAttribute(HttpClientConstants.BODY_READABLE, Boolean.TRUE);
    }
  }

  /** Returns the position of the {@code @RequestBody} parameter, or -1 when there is none. */
  private int bodyPositionOf(MethodParameter... parameters) {
    return IntStream.range(0, parameters.length)
        .filter(position -> parameters[position].hasParameterAnnotation(RequestBody.class))
        .findFirst()
        .orElse(-1);
  }
}
