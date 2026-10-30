package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import com.exepex.stratum.web.WebPropertiesCustomizer;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Settings and IDs shared by the web module's tests. */
@UtilityClass
public class WebTestData {

  /** A trace ID as tracing makes one. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A correlation ID as a caller sends one. */
  public static final String CORRELATION_ID = "checkout-777";

  /**
   * Returns web settings whose error statuses map each code to its status.
   *
   * @param statusesByCode the HTTP status of each code
   * @return the settings
   */
  public static WebProperties withErrorStatuses(Map<String, Integer> statusesByCode) {
    return WebProperties.builder()
        .error(WebProperties.ErrorSettings.builder().statuses(statusesByCode).build())
        .build();
  }

  /**
   * Runs the work with the customizers listed in a {@code META-INF/spring.factories}, as a
   * service's own file lists them. The file is on the thread's context class loader, which Spring
   * Boot and Stratum read it from.
   *
   * @param folder an empty folder for the file
   * @param customizers the customizer classes to list
   * @param work what to run
   * @param <T> what the work returns
   * @return what the work returned
   * @throws IOException if the file cannot be written
   */
  public static <T> T withCustomizers(
      Path folder, List<Class<? extends WebPropertiesCustomizer>> customizers, Supplier<T> work)
      throws IOException {
    var factories = folder.resolve("META-INF/spring.factories");
    Files.createDirectories(factories.getParent());
    Files.writeString(
        factories,
        WebPropertiesCustomizer.class.getName()
            + "="
            + customizers.stream().map(Class::getName).collect(Collectors.joining(",")));
    var thread = Thread.currentThread();
    var originalClassLoader = thread.getContextClassLoader();
    try (var classLoader =
        new URLClassLoader(new URL[] {folder.toUri().toURL()}, originalClassLoader)) {
      thread.setContextClassLoader(classLoader);
      return work.get();
    } finally {
      thread.setContextClassLoader(originalClassLoader);
    }
  }
}
