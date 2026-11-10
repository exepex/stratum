package com.exepex.stratum.restclient;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Copies a client's {@code copy-headers} from the request the service is answering onto a call, on
 * the caller's thread. Outside a request, such as in a scheduled job, on an {@code @Async} thread
 * or in a service without the servlet API, it copies nothing. No servlet type appears in a field or
 * a signature here, so a service without the servlet API never loads one.
 */
@RequiredArgsConstructor
final class CopiedHeaders {

  /** The names of the headers to copy, each once. */
  private final List<String> names;

  /**
   * Adds each named header of the current request to the call's headers, unless the call already
   * has one of that name, such as from the method or from {@code headers}. Empty values are not
   * copied.
   *
   * @param headers the call's headers
   */
  void copyInto(HttpHeaders headers) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
      copyFrom(current, headers);
    }
  }

  /** Copies the named headers from a servlet request. */
  private void copyFrom(ServletRequestAttributes current, HttpHeaders headers) {
    var request = current.getRequest();
    // A loop, not a stream: a lambda would take the servlet request as a parameter of a method of
    // this class, which a service without the servlet API cannot load.
    for (var name : names) {
      var values =
          Collections.list(request.getHeaders(name)).stream()
              .filter(Predicate.not(String::isEmpty))
              .toList();
      if (!headers.containsHeader(name) && !values.isEmpty()) {
        headers.put(name, values);
      }
    }
  }
}
