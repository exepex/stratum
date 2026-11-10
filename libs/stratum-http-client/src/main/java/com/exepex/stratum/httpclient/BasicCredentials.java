package com.exepex.stratum.httpclient;

import org.jspecify.annotations.Nullable;

/**
 * The user name and password a client sends with HTTP Basic authentication. Both are required. Take
 * them from placeholders, such as {@code ${PARTNER_PASSWORD}}, never from the file itself.
 *
 * @param username The user name the other service expects. None by default.
 * @param password The password, from a placeholder such as ${PARTNER_PASSWORD}. None by default.
 */
public record BasicCredentials(@Nullable String username, @Nullable String password) {

  /**
   * Returns whether both the user name and the password are set.
   *
   * @return whether the credentials are complete
   */
  boolean isComplete() {
    return username != null && password != null;
  }

  /**
   * Shows that credentials are set, never their values, which are secrets.
   *
   * @return a text without the user name or password
   */
  @Override
  public String toString() {
    return "BasicCredentials[hidden]";
  }
}
