package com.exepex.stratum.test.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.RedisContainerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(classes = RedisContainerConfigurationIntegrationTest.RedisApplication.class)
@Import(RedisContainerConfiguration.class)
@DisplayName("RedisContainerConfiguration connects a service's tests to Redis")
class RedisContainerConfigurationIntegrationTest {

  /** The application's Redis client, which no {@code spring.data.redis.*} property configures. */
  @Autowired private StringRedisTemplate redis;

  /** The container the configuration started. */
  @Autowired private GenericContainer<?> container;

  @Test
  @DisplayName("A value set through the client is read back from the pinned Redis")
  void storesAndReadsValueOnPinnedRedis() {
    redis.opsForValue().set("order:A-1", "2");

    assertThat(redis.opsForValue().get("order:A-1")).isEqualTo("2");
    assertThat(container.getDockerImageName()).isEqualTo(PinnedImages.image("redis"));
  }

  /**
   * A service with Redis and nothing else. The data source is excluded because this module's tests
   * also carry the JDBC support and PostgreSQL driver of the PostgreSQL test.
   */
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class RedisApplication {}
}
