package com.exepex.stratum.core.version;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Logs one {@code SX-CORE-1005} warning at startup when the running Spring Boot version is outside
 * the line this Stratum release supports, such as {@code 4.1.x}. It never stops startup: the
 * service's parent POM decides the version (ADR 0007). Any patch of the supported line is quiet,
 * and so is a version Spring Boot cannot report, such as from a repackaged jar.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SpringBootVersionCheck implements EnvironmentPostProcessor {

  /** The file the build fills in with the Spring Boot version Stratum is tested with. */
  private static final String SUPPORTED_VERSION_FILE =
      "META-INF/stratum/spring-boot-version.properties";

  /** The key of that version in the file. */
  private static final String SUPPORTED_VERSION_KEY = "supported-version";

  /** The major and minor part of a version, such as {@code 4.1} in {@code 4.1.2}. */
  private static final Pattern MAJOR_AND_MINOR = Pattern.compile("^\\d+\\.\\d+");

  /** Gives the log for the warning, which waits until logging is ready. */
  private final DeferredLogFactory logFactory;

  /** The Spring Boot version Stratum is built and tested with, such as {@code 4.1.2}. */
  private final String supportedVersion;

  /** The Spring Boot version the service runs on, or {@code null} when Spring Boot cannot tell. */
  private final @Nullable String runningVersion;

  /**
   * Makes the check for the running application; Spring Boot calls this.
   *
   * @param logFactory gives the log for the warning
   */
  public SpringBootVersionCheck(DeferredLogFactory logFactory) {
    this(logFactory, readSupportedVersion(), SpringBootVersion.getVersion());
  }

  /**
   * Logs the warning when the running version is known and outside the supported line.
   *
   * @param environment the application's environment, not used
   * @param application the application being started, not used
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    var supportedLine = lineOf(supportedVersion);
    var log = logFactory.getLog(SpringBootVersionCheck.class);
    if (runningVersion != null
        && !lineOf(runningVersion).equals(supportedLine)
        && log.isWarnEnabled()) {
      var report =
          ErrorReport.of(
              CoreErrorCode.SPRING_BOOT_VERSION_UNSUPPORTED,
              "found " + runningVersion + ", supported " + supportedLine + ".x");
      // The report already removed control characters; this also satisfies SpotBugs.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
  }

  /** Returns the major and minor part of a version, or the whole version if it has none. */
  private String lineOf(String version) {
    var matcher = MAJOR_AND_MINOR.matcher(version);
    return matcher.find() ? matcher.group() : version;
  }

  /** Reads the Spring Boot version that the build wrote into Stratum's jar. */
  private static String readSupportedVersion() {
    try {
      return PropertiesLoaderUtils.loadProperties(new ClassPathResource(SUPPORTED_VERSION_FILE))
          .getProperty(SUPPORTED_VERSION_KEY);
    } catch (IOException missingFile) {
      throw new UncheckedIOException(missingFile);
    }
  }
}
