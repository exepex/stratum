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

  @Test
  @DisplayName("every container's image is filled in, carries a tag and is never latest")
  void theBuildFilledInEveryImage() throws IOException {
    var images = readWrittenImages();

    assertThat(images.stringPropertyNames())
        .containsExactlyInAnyOrder("postgres.image", "redis.image");
    images
        .stringPropertyNames()
        .forEach(
            key ->
                assertThat(images.getProperty(key))
                    .isNotBlank()
                    .doesNotContain("@")
                    .contains(":")
                    .doesNotEndWith(":latest"));
  }

  /** Reads the images the build wrote into the jar, straight from the classpath. */
  private Properties readWrittenImages() throws IOException {
    return PropertiesLoaderUtils.loadProperties(new ClassPathResource(ContainerImages.IMAGES_FILE));
  }
}
