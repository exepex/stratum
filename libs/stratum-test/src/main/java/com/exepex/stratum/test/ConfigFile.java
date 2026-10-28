package com.exepex.stratum.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads a configuration file from the test classpath into an {@code ApplicationContextRunner}. With
 * it, an auto-configuration test proves that its settings bind from both {@code
 * application.properties} and {@code application.yml}, as ST-TEST-20 requires.
 *
 * <pre>{@code
 * contextRunner
 *     .withInitializer(ConfigFile.load("logging/application.yml"))
 *     .run(context -> ...);
 * }</pre>
 *
 * <p>It reads the file with Spring Boot's own loaders, so the file is read as it would be at a
 * service's startup. Its values win over every other value in the runner's environment, including
 * those from {@code withPropertyValues}.
 */
@UtilityClass
public class ConfigFile {

  /** Spring Boot's loaders: one for {@code .properties} and {@code .xml}, one for YAML. */
  private static final List<PropertySourceLoader> LOADERS =
      List.of(new PropertiesPropertySourceLoader(), new YamlPropertySourceLoader());

  /**
   * Returns an initializer that adds a configuration file to the context's environment. Pass it to
   * the runner's {@code withInitializer}.
   *
   * @param file the file's location on the classpath, such as {@code logging/application.yml}; it
   *     ends in {@code .properties}, {@code .xml}, {@code .yml} or {@code .yaml}
   * @return the initializer
   * @throws IllegalArgumentException if the file has none of those endings, or is not on the
   *     classpath
   */
  public static ApplicationContextInitializer<ConfigurableApplicationContext> load(String file) {
    var loader = loaderFor(file);
    var resource = existing(file);
    return context -> addFirst(context.getEnvironment(), loader, resource);
  }

  /**
   * Finds the Spring Boot loader that reads the file, by its ending.
   *
   * @param file the file's location on the classpath
   * @return the loader
   * @throws IllegalArgumentException if no loader reads files with that ending
   */
  private static PropertySourceLoader loaderFor(String file) {
    return LOADERS.stream()
        .filter(loader -> reads(loader, file))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Not a .properties, .xml, .yml or .yaml file: " + file));
  }

  /**
   * Tells whether a loader reads files with the file's ending.
   *
   * @param loader one of Spring Boot's loaders
   * @param file the file's location on the classpath
   * @return {@code true} if the loader reads it
   */
  private static boolean reads(PropertySourceLoader loader, String file) {
    return Arrays.stream(loader.getFileExtensions())
        .anyMatch(extension -> file.endsWith("." + extension));
  }

  /**
   * Finds the file on the classpath.
   *
   * @param file the file's location on the classpath
   * @return the file
   * @throws IllegalArgumentException if the file is not on the classpath
   */
  private static ClassPathResource existing(String file) {
    var resource = new ClassPathResource(file);
    if (!resource.exists()) {
      throw new IllegalArgumentException("No configuration file on the classpath at " + file);
    }
    return resource;
  }

  /**
   * Reads the file and puts its values ahead of every other value in the environment. In a YAML
   * file with several documents, a later document wins, as at startup.
   *
   * @param environment the environment of the context under test
   * @param loader the loader that reads the file
   * @param resource the file
   * @throws UncheckedIOException if the file cannot be read
   */
  private static void addFirst(
      ConfigurableEnvironment environment,
      PropertySourceLoader loader,
      ClassPathResource resource) {
    try {
      loader.load(resource.getPath(), resource).forEach(environment.getPropertySources()::addFirst);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the configuration file " + resource.getPath(), e);
    }
  }
}
