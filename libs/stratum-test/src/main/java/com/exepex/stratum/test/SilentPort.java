package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * A local port that takes no new connection, so each connect to it times out, as with a host that
 * is down. It listens without accepting and fills its one-place backlog, after which the system
 * drops every new connect. Used to prove that a client's connect timeout works.
 */
@StratumInternal
public final class SilentPort implements AutoCloseable {

  /** How long a filling connect may take before the backlog counts as full, in milliseconds. */
  private static final int FILL_TIMEOUT_MILLIS = 200;

  /** The most connects tried while filling the backlog. */
  private static final int MAX_FILLS = 64;

  /** Listens without accepting. */
  private final ServerSocket listener;

  /** The connects that fill the backlog; kept open while the port is silent. */
  private final List<Socket> fillers = new ArrayList<>();

  /** Opens the port on the loopback address and fills its backlog. */
  public SilentPort() {
    try {
      listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      // A loop rather than a stream: it stops at the first connect that times out.
      var fills = 0;
      while (fills < MAX_FILLS && fill()) {
        fills++;
      }
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
    fillers.forEach(this::closeFiller);
    listener.close();
  }

  /**
   * Adds one connect to the backlog; returns false once a connect times out, as the backlog is
   * full. Every socket, the timed-out one included, stays in the list and is closed with the port.
   */
  private boolean fill() throws IOException {
    fillers.add(new Socket());
    try {
      fillers.getLast().connect(listener.getLocalSocketAddress(), FILL_TIMEOUT_MILLIS);
      return true;
    } catch (SocketTimeoutException _) {
      return false;
    }
  }

  /** Closes one filling connect. */
  private void closeFiller(Socket filler) {
    try {
      filler.close();
    } catch (IOException closeFailure) {
      throw new UncheckedIOException(closeFailure);
    }
  }
}
