package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
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
  @DisplayName(
      "every container has its name and tag filled in and the build combines them into the image,"
          + " pinned to a tag and never latest")
  void theBuildFilledInEveryImage() throws IOException {
    var images = readWrittenImages();

    assertThat(images.stringPropertyNames())
        .containsExactlyInAnyOrder(
            "postgres.name",
            "postgres.tag",
            "postgres.image",
            "redis.name",
            "redis.tag",
            "redis.image");
    for (String container : List.of("postgres", "redis")) {
      var name = images.getProperty(container + ".name");
      var tag = images.getProperty(container + ".tag");
      assertThat(name).isNotBlank().doesNotContain("@");
      assertThat(tag).isNotBlank().doesNotContain("@").isNotEqualTo("latest");
      assertThat(images.getProperty(container + ".image")).isEqualTo(name + ":" + tag);
    }
  }

  /** Reads the images the build wrote into the jar, straight from the classpath. */
  private static Properties readWrittenImages() throws IOException {
    return PropertiesLoaderUtils.loadProperties(new ClassPathResource(IMAGES_FILE));
  }
}
