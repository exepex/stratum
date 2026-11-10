package com.exepex.stratum.restclient.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class loader that stands for a service without the servlet API: it defines the starter's own
 * classes, and Spring's {@code ServletRequestAttributes}, itself, child first, so that every class
 * they need is looked up here, and it refuses each {@code jakarta.servlet} class, recording its
 * name. Every other class comes from the test's own loader, the thread's context loader.
 */
public final class ServletFreeClassLoader extends ClassLoader {

  /** The package of the starter's classes, defined here. */
  private static final String STARTER_PACKAGE = "com.exepex.stratum.restclient.";

  /** Spring's class that holds a servlet request, defined here. */
  private static final String SERVLET_REQUEST_ATTRIBUTES =
      "org.springframework.web.context.request.ServletRequestAttributes";

  /** The start of the servlet API's class names, which are refused. */
  private static final String SERVLET_API = "jakarta.servlet.";

  /** The names of the servlet classes that were asked for, in order. */
  private final List<String> refused = new CopyOnWriteArrayList<>();

  /** Lets one class be defined at a time, so that none is defined twice. */
  private final ReentrantLock defining = new ReentrantLock();

  /** Makes the loader on top of the test's own. */
  public ServletFreeClassLoader() {
    super(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Returns the names of the servlet classes asked for so far.
   *
   * @return the names, in order
   */
  public List<String> refusedNames() {
    return List.copyOf(refused);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith(SERVLET_API)) {
      refused.add(name);
      throw new ClassNotFoundException(name);
    }
    if (!name.startsWith(STARTER_PACKAGE) && !SERVLET_REQUEST_ATTRIBUTES.equals(name)) {
      return super.loadClass(name, resolve);
    }
    defining.lock();
    try {
      var loaded = findLoadedClass(name);
      return loaded != null ? loaded : defineHere(name);
    } finally {
      defining.unlock();
    }
  }

  /** Defines a class from the bytes the test's own loader finds for it. */
  private Class<?> defineHere(String name) throws ClassNotFoundException {
    var resource = name.replace('.', '/') + ".class";
    try (var bytes = Objects.requireNonNull(getParent()).getResourceAsStream(resource)) {
      if (bytes == null) {
        throw new ClassNotFoundException(name);
      }
      var code = bytes.readAllBytes();
      return defineClass(name, code, 0, code.length);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
