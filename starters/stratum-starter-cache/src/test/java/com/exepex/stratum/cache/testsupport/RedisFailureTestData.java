package com.exepex.stratum.cache.testsupport;

import io.lettuce.core.RedisException;
import lombok.experimental.UtilityClass;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** The Redis failures the retry gate tests throw, as Spring Data translates Lettuce's errors. */
@UtilityClass
public class RedisFailureTestData {

  /** A connect that failed. */
  public static final RuntimeException CONNECT_FAILED =
      new RedisConnectionFailureException("Unable to connect to Redis");

  /** A command refused because the connection is down. */
  public static final RuntimeException REJECTED =
      new RedisSystemException(
          "Redis exception", new RedisException("Currently not connected. Commands are rejected."));

  /** A command that was in flight when the connection dropped. */
  public static final RuntimeException DISCONNECTED =
      new RedisSystemException("Redis exception", new RedisException("Connection disconnected"));

  /** A command that timed out. */
  public static final RuntimeException TIMED_OUT =
      new QueryTimeoutException("Redis command timed out");

  /** A failure that Redis answered with, so it was reached. */
  public static final RuntimeException ANSWERED =
      new InvalidDataAccessApiUsageException("WRONGTYPE Operation against a key");
}
