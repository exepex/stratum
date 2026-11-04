package com.exepex.stratum.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * The Docker images of Stratum's test containers, which the build writes into this jar from the
 * root {@code pom.xml}, so that every image is pinned in one place (ST-ENG-60, ST-TEST-32).
 */
@UtilityClass
class ContainerImages {

  /** The properties file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  /** The images, read once from this jar. */
  private static final Properties IMAGES = readImages();

  /** The PostgreSQL image every Stratum test runs against, pinned in the root {@code pom.xml}. */
  static final String POSTGRES = IMAGES.getProperty("postgres");

  /** The Redis image every Stratum test runs against, pinned in the root {@code pom.xml}. */
  static final String REDIS = IMAGES.getProperty("redis");

  /** Reads the images the build wrote into this jar. */
  private static Properties readImages() {
    try {
      return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE));
    } catch (IOException missingFile) {
      throw new UncheckedIOException(missingFile);
    }
  }
}
