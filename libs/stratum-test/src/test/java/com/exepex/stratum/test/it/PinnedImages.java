package com.exepex.stratum.test.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * The container images the build pinned in the root {@code pom.xml}, for the integration tests that
 * check the running containers against them.
 */
@UtilityClass
class PinnedImages {

  /** The file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  /**
   * Returns the server version the named container runs, derived from the tag of its pinned image:
   * {@code 18.6} from {@code postgres:18.6-alpine}. Works with the exact {@code version} or {@code
   * version-distro} tags Stratum pins, never a floating tag (ST-TEST-32).
   *
   * @param container the container's key in the images file, such as {@code postgres}
   * @return the version the container's server reports
   */
  static String serverVersion(String container) {
    var pinned = readImage(container);
    var tag = pinned.substring(pinned.indexOf(':') + 1);
    return tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
  }

  /** Reads the named container's pinned image from the file the build wrote into the jar. */
  private static String readImage(String container) {
    try {
      return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE))
          .getProperty(container);
    } catch (IOException missingFile) {
      throw new UncheckedIOException(missingFile);
    }
  }
}
