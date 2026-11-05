package com.exepex.stratum.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * Starts a Redis server in Docker for a test and points the application's Redis client at it, so a
 * test sets no {@code spring.data.redis.*} property. Import it with
 * {@code @Import(RedisContainerConfiguration.class)}. The container lives as long as the test's
 * application context, which Spring reuses across test classes with the same configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfiguration {

  /** The port Redis listens on inside the container. */
  private static final int REDIS_PORT = 6379;

  /**
   * Creates the Redis container, which Spring Boot starts and connects the application to. Its
   * image is pinned in the root {@code pom.xml}, never {@code latest}. The connection name {@code
   * redis} is what lets Spring Boot find its Redis connection details for a container declared as a
   * bean.
   *
   * @return the container
   */
  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(ContainerImages.REDIS_IMAGE).withExposedPorts(REDIS_PORT);
  }
}
