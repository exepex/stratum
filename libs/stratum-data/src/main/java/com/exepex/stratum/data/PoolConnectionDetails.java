package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

/**
 * A named pool's connection, from its {@code url}, {@code username} and {@code password} keys. Its
 * {@code toString} is {@code Object}'s, so the password never reaches a log or a message.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public final class PoolConnectionDetails implements JdbcConnectionDetails {

  /** The pool's JDBC URL. */
  private final String jdbcUrl;

  /** The user name the pool logs in with, or {@code null}. */
  private final @Nullable String username;

  /** The password the pool logs in with, or {@code null}. */
  private final @Nullable String password;
}
