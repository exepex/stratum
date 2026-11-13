package com.exepex.stratum.data.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

/**
 * A database address on this machine that accepts connections into the system's backlog and never
 * reads or answers them, as a hung server does. Closing it resets those connections, which ends the
 * driver threads still reading them.
 */
public final class SilentDatabase implements AutoCloseable {

  /**
   * The settings that let a service start against it: no schema set-up, and no JDBC metadata read
   * at startup.
   */
  public static final List<String> STARTUP_PROPERTIES =
      List.of(
          "spring.sql.init.mode=never",
          "spring.jpa.hibernate.ddl-auto=none",
          "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
          "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect");

  /** The name the PostgreSQL driver gives the thread of each connection attempt with a limit. */
  private static final String DRIVER_THREAD = "PostgreSQL JDBC driver connection thread";

  /** How many connections the system accepts without anyone reading them. */
  private static final int BACKLOG = 50;

  /** The host: this machine's own loopback address. */
  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  /** The listening socket, whose backlog holds the connections nobody answers. */
  private final ServerSocket socket;

  /**
   * Opens the address on a free port.
   *
   * @throws IOException if no port is free
   */
  public SilentDatabase() throws IOException {
    socket = new ServerSocket(0, BACKLOG, LOOPBACK);
  }

  /**
   * Returns the host's address, which no log line may carry.
   *
   * @return the address, such as {@code 127.0.0.1}
   */
  public String host() {
    return LOOPBACK.getHostAddress();
  }

  /**
   * Returns the port, which no log line may carry.
   *
   * @return the port
   */
  public int port() {
    return socket.getLocalPort();
  }

  /**
   * Returns a PostgreSQL URL with no login limit of its own.
   *
   * @return the URL
   */
  public String url() {
    return "jdbc:postgresql://" + host() + ":" + port() + "/orders";
  }

  /**
   * Returns how many of the driver's connection threads are alive in this JVM.
   *
   * @return the number of threads
   */
  public static long driverThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> DRIVER_THREAD.equals(thread.getName()) && thread.isAlive())
        .count();
  }

  /**
   * Closes the address and resets every connection in its backlog.
   *
   * @throws IOException if the socket cannot be closed
   */
  @Override
  public void close() throws IOException {
    socket.close();
  }
}
