package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.ReferencePage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.View;

/**
 * Spring MVC's view of Stratum's {@link ReferencePage}, named {@code error} like Spring Boot's
 * whitelabel page, which it replaces.
 */
public class ReferenceErrorPage implements View {

  /**
   * Returns the page's content type.
   *
   * @return {@code text/html;charset=UTF-8}
   */
  @Override
  public String getContentType() {
    return ReferencePage.CONTENT_TYPE;
  }

  /**
   * Writes the page, unless the response has been sent already.
   *
   * @param model the error model: Stratum's, with {@code status}, {@code title} and, when known,
   *     {@code traceId}, or Spring Boot's, when the page takes the trace ID from the request
   * @param request the request
   * @param response the response
   * @throws IOException if the page cannot be written
   */
  @Override
  public void render(
      @Nullable Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setContentType(getContentType());
    // ReferencePage has escaped every value in the page.
    StreamUtils.copy(
        ReferencePage.of(
            model == null ? Map.of() : model,
            Objects.toString(request.getAttribute(RequestIdsFilter.TRACE_ID_ATTRIBUTE), null)),
        StandardCharsets.UTF_8,
        response.getOutputStream());
  }
}
