package com.exepex.stratum.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * The Docker images of Stratum's test containers, which the build writes into this jar as a name, a
 * tag and the full image of each container, from the root {@code pom.xml}, so that every image is
 * pinned in one place (ST-ENG-60, ST-TEST-32).
 */
@UtilityClass
class ContainerImages {

  /** The properties file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  /** The images, read once from this jar. */
  private static final Properties IMAGES = readImages();

  /** The name of the PostgreSQL image, pinned in the root {@code pom.xml}. */
  static final String POSTGRES_NAME = IMAGES.getProperty("postgres.name");

  /** The tag of the PostgreSQL image, pinned in the root {@code pom.xml}, never {@code latest}. */
  static final String POSTGRES_TAG = IMAGES.getProperty("postgres.tag");

  /** The PostgreSQL image every Stratum test runs against: name and tag, combined by the build. */
  static final String POSTGRES_IMAGE = IMAGES.getProperty("postgres.image");

  /** The name of the Redis image, pinned in the root {@code pom.xml}. */
  static final String REDIS_NAME = IMAGES.getProperty("redis.name");

  /** The tag of the Redis image, pinned in the root {@code pom.xml}, never {@code latest}. */
  static final String REDIS_TAG = IMAGES.getProperty("redis.tag");

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
