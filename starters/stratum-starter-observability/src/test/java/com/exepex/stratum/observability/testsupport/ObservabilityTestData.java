package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.springframework.mock.env.MockEnvironment;

/** Sample metrics, environments and Java configuration shared by the observability tests. */
@UtilityClass
public class ObservabilityTestData {

  /** The name of every sample metric. */
  public static final String METRIC_NAME = "orders.placed";

  /** The value of the sample tag, made up, which the guard must never report. */
  public static final String TAG_VALUE = "user-7731";

  /**
   * Returns a counter's name and tags, with one tag.
   *
   * @param tagName the tag's name; its value is {@link #TAG_VALUE}
   * @return the counter's ID, as a meter filter sees it
   */
  public static Meter.Id counterTaggedWith(String tagName) {
    return new Meter.Id(METRIC_NAME, Tags.of(tagName, TAG_VALUE), null, null, Meter.Type.COUNTER);
  }

  /**
   * Returns an environment with the module switched on and nothing else set.
   *
   * @return the environment, which a test can add properties to
   */
  public static MockEnvironment environmentWithModuleOn() {
    return new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, "true");
  }

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
