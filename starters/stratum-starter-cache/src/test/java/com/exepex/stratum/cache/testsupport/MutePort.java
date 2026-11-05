package com.exepex.stratum.cache.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * A local port that takes every connect but never answers, as a Redis host that hangs after a
 * restart: the connect succeeds, and the client's first command, its handshake, waits for its
 * timeout. The system completes each connect into the listen backlog, which is never accepted.
 */
public final class MutePort implements AutoCloseable {

  /** How many connects the backlog holds; more than any test makes. */
  private static final int BACKLOG = 50;

  /** Listens without accepting. */
  private final ServerSocket listener;

  /** Opens the port on the loopback address. */
  public MutePort() {
    try {
      listener = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
    } catch (IOException openFailure) {
      throw new UncheckedIOException(openFailure);
    }
  }

  /**
   * Returns the host to connect to.
   *
   * @return the loopback address
   */
  public String host() {
    return listener.getInetAddress().getHostAddress();
  }

  /**
   * Returns the port to connect to.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }

  @Override
  public void close() throws IOException {
    listener.close();
  }
}
