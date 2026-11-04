package com.exepex.stratum.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * The Docker images of Stratum's test containers, which the build writes into this jar from the
 * root {@code pom.xml}, so that every image is pinned in one place (ST-ENG-60, ST-TEST-32). The
 * properties file also carries each image's name and tag separately, for whoever needs them.
 */
@UtilityClass
class ContainerImages {

  /** The properties file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  /** The images, read once from this jar. */
  private static final Properties IMAGES = readImages();

  /** The PostgreSQL image every Stratum test runs against: name and tag, combined by the build. */
  static final String POSTGRES_IMAGE = IMAGES.getProperty("postgres.image");

  /** The Redis image every Stratum test runs against: name and tag, combined by the build. */
  static final String REDIS_IMAGE = IMAGES.getProperty("redis.image");

  /** Reads the images the build wrote into this jar. */
  private static Properties readImages() {
    try {
      return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE));
    } catch (IOException missingFile) {
      throw new UncheckedIOException(missingFile);
    }
  }
}
