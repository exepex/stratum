package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Runs a test with a service's Java configuration of the observability module. */
@UtilityClass
public class JavaConfigurationTestData {

  /**
   * Runs the work with the customizers listed in a {@code META-INF/spring.factories}, as a
   * service's own file lists them. The file is on the thread's context class loader, which the
   * module reads it from.
   *
   * @param folder an empty folder for the file
   * @param customizers the customizer classes to list
   * @param work what to run
   * @param <T> what the work returns
   * @return what the work returned
   * @throws IOException if the file cannot be written
   */
  public static <T> T withCustomizers(
      Path folder,
      List<Class<? extends ObservabilityPropertiesCustomizer>> customizers,
      Supplier<T> work)
      throws IOException {
    var factories = folder.resolve("META-INF/spring.factories");
    Files.createDirectories(factories.getParent());
    Files.writeString(
        factories,
        ObservabilityPropertiesCustomizer.class.getName()
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
