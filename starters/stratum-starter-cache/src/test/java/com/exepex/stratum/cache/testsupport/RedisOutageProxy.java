package com.exepex.stratum.cache.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A TCP proxy between the service and Redis that a test can cut and restore on the same port, so a
 * test sees what a service sees when Redis goes away after it connected: open connections drop and
 * new ones are refused, until the proxy is restored.
 */
public final class RedisOutageProxy implements AutoCloseable {

  /** The host Redis listens on. */
  private final String redisHost;

  /** The port Redis listens on. */
  private final int redisPort;

  /** The port the service connects to, kept across a cut. */
  private final int listeningPort;

  /** Every socket the proxy opened, closed on a cut. */
  private final List<Socket> openSockets = new CopyOnWriteArrayList<>();

  /** Runs the accept loop and the relaying of each connection. */
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  /** Makes a cut and a restore one step each. */
  private final ReentrantLock lock = new ReentrantLock();

  /** The socket that accepts the service's connections; closed while cut. */
  private ServerSocket listener;

  /**
   * Starts the proxy on a free port.
   *
   * @param redisHost the host Redis listens on
   * @param redisPort the port Redis listens on
   * @throws IOException if no port can be opened
   */
  public RedisOutageProxy(String redisHost, int redisPort) throws IOException {
    this.redisHost = redisHost;
    this.redisPort = redisPort;
    this.listener = new ServerSocket(0);
    this.listeningPort = listener.getLocalPort();
    workers.execute(this::acceptUntilClosed);
  }

  /**
   * Returns the port the service connects to.
   *
   * @return the port
   */
  public int port() {
    return listeningPort;
  }

  /**
   * Drops every open connection and refuses new ones, as when Redis goes away.
   *
   * @throws IOException if the listening socket cannot be closed
   */
  public void cut() throws IOException {
    lock.lock();
    try {
      listener.close();
      openSockets.forEach(this::closeSocket);
      openSockets.clear();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Accepts connections on the same port again, as when Redis comes back; does nothing when not
   * cut.
   *
   * @throws IOException if the port cannot be opened again
   */
  public void restore() throws IOException {
    lock.lock();
    try {
      if (!listener.isClosed()) {
        return;
      }
      listener = new ServerSocket();
      listener.setReuseAddress(true);
      listener.bind(new InetSocketAddress(listeningPort));
      workers.execute(this::acceptUntilClosed);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    cut();
    workers.shutdownNow();
  }

  /** Relays each accepted connection until the listener is closed. */
  private void acceptUntilClosed() {
    // A loop rather than a stream: it blocks on accept until the listener is closed.
    while (!listener.isClosed()) {
      try {
        relayLater(listener.accept());
      } catch (IOException _) {
        return;
      }
    }
  }

  /** Relays an accepted connection on its own thread. */
  private void relayLater(Socket service) {
    workers.submit(() -> relay(service));
  }

  /** Closes one socket, as a cut drops every connection. */
  private void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException closeFailure) {
      throw new UncheckedIOException(closeFailure);
    }
  }

  /** Relays one connection both ways through a new connection to Redis, until either closes. */
  private long relay(Socket service) throws IOException {
    try (service;
        var redis = new Socket(redisHost, redisPort)) {
      openSockets.add(service);
      openSockets.add(redis);
      workers.submit(() -> redis.getInputStream().transferTo(service.getOutputStream()));
      return service.getInputStream().transferTo(redis.getOutputStream());
    }
  }
}
