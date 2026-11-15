package com.exepex.stratum.datajpa.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;

@DisplayName("Without HikariCP on the class path, the routing registrar loads none of its classes")
class RoutingRegistrarWithoutHikariTest {

  /** The name of the JPA repository's bean the probe defines. */
  private static final String REPOSITORY_BEAN = "notes";

  /** HikariCP's jar, recognised by its file name. */
  private static final String HIKARI_JAR = "HikariCP";

  /** The test's class path without HikariCP, under the platform's classes only. */
  private final URLClassLoader withoutHikari =
      new URLClassLoader(classPathWithoutHikari(), ClassLoader.getPlatformClassLoader());

  /** Closes the class loader. */
  @AfterEach
  void close() throws IOException {
    withoutHikari.close();
  }

  /** Returns the test's class path, which Surefire names, with HikariCP's jar left out. */
  private static URL[] classPathWithoutHikari() {
    var classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return Arrays.stream(classPath.split(File.pathSeparator))
        .filter(entry -> !Path.of(entry).getFileName().toString().startsWith(HIKARI_JAR))
        .map(RoutingRegistrarWithoutHikariTest::url)
        .toArray(URL[]::new);
  }

  /** Returns a class path entry's URL. */
  private static URL url(String entry) {
    try {
      return Path.of(entry).toUri().toURL();
    } catch (MalformedURLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /**
   * Runs the probe in the class loader without HikariCP, which is also the thread's, as an
   * application's is, and returns its outcome.
   */
  private String probe(String packages) throws ReflectiveOperationException {
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    thread.setContextClassLoader(withoutHikari);
    try {
      var probe = withoutHikari.loadClass(Probe.class.getName());
      return String.valueOf(
          probe
              .getMethod("apply", String.class)
              .invoke(probe.getConstructor().newInstance(), packages));
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  @Nested
  @DisplayName("the class loader of the probe")
  class Loader {

    @Test
    @DisplayName("has no HikariCP class")
    void noHikari() {
      assertThatThrownBy(() -> withoutHikari.loadClass("com.zaxxer.hikari.HikariConfig"))
          .isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("the registrar")
  class Registrar {

    @Test
    @DisplayName("with no mapping, registers nothing and fails nothing")
    void noMapping() throws ReflectiveOperationException {
      assertThat(probe("")).isEqualTo(REPOSITORY_BEAN);
    }

    @Test
    @DisplayName("with a mapping, fails with SX-DATA-1007, since no named pool exists")
    void mapping() throws ReflectiveOperationException {
      assertThat(probe(Probe.class.getPackageName()))
          .contains(DataErrorCode.ROUTING_INVALID.getCode())
          .contains("batch.packages maps packages to a pool that does not exist");
    }
  }

  /**
   * Runs the registrar over one JPA repository's definition, in the thread's class loader, which
   * loaded it, and returns the names of the beans defined after it, or its failure's message.
   */
  public static final class Probe {

    /**
     * Runs the registrar.
     *
     * @param packages the packages the pool batch maps, or an empty text for no mapping
     * @return the bean names, joined by commas, or the failure's message
     */
    public String apply(String packages) {
      var environment = new StandardEnvironment();
      var key = DataConstants.SOURCES + ".batch." + DataConstants.PACKAGES_KEY;
      Map<String, Object> properties = packages.isEmpty() ? Map.of() : Map.of(key, packages);
      environment.getPropertySources().addFirst(new MapPropertySource("probe", properties));
      var registry = new DefaultListableBeanFactory();
      var repository = new RootBeanDefinition(JpaRepositoryFactoryBean.class);
      repository.getConstructorArgumentValues().addIndexedArgumentValue(0, Notes.class.getName());
      registry.registerBeanDefinition(REPOSITORY_BEAN, repository);
      var registrar = new RoutingRegistrar();
      registrar.setEnvironment(environment);
      registrar.setBeanClassLoader(Thread.currentThread().getContextClassLoader());
      try {
        registrar.postProcessBeanDefinitionRegistry(registry);
        return String.join(",", registry.getBeanDefinitionNames());
      } catch (StratumException failure) {
        return String.valueOf(failure.getMessage());
      }
    }
  }

  /** A JPA repository in the probe's package. */
  interface Notes extends Repository<Object, Long> {}
}
