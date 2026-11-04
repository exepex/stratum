package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

@DisplayName("The build writes the pinned container images into stratum-test's jar")
class ContainerImagesTest {

  /** The file the build fills in from the root {@code pom.xml}. */
  private static final String IMAGES_FILE = "META-INF/stratum/test-container-images.properties";

  @Test
  @DisplayName("every container has its image filled in, pinned to a tag and never latest")
  void theBuildFilledInEveryImage() throws IOException {
    var images = readWrittenImages();

    assertThat(images.stringPropertyNames()).contains("postgres", "redis");
    images
        .stringPropertyNames()
        .forEach(
            container ->
                assertThat(images.getProperty(container))
                    .isNotBlank()
                    .doesNotContain("@")
                    .contains(":")
                    .doesNotEndWith(":latest"));
  }

  /** Reads the images the build wrote into the jar, straight from the classpath. */
  private static Properties readWrittenImages() throws IOException {
    return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE));
  }
}
