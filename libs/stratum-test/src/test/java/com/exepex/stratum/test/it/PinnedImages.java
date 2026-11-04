package com.exepex.stratum.test.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * The container images the build pinned in the root {@code pom.xml}, for the integration tests that
 * check the running containers against them. Reads them straight from the properties file the build
 * filled in; nothing is split or combined in Java.
 */
@UtilityClass
class PinnedImages {

  /** The file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  /** The pinned images, read once from the jar. */
  private static final Properties IMAGES = readImages();

  /**
   * Returns the full image, name and tag, the build pinned for the container.
   *
   * @param container the container's key in the images file, such as {@code postgres}
   * @return the pinned image, such as {@code postgres:18.6-alpine}
   */
  static String image(String container) {
    return IMAGES.getProperty(container + ".image");
  }

  /** Reads the images the build wrote into the jar. */
  private static Properties readImages() {
    try {
      return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE));
    } catch (IOException missingFile) {
      throw new UncheckedIOException(missingFile);
    }
  }
}
