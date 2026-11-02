package com.exepex.stratum.logging.testsupport;

import java.util.Iterator;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Logged values whose {@code toString()} misbehaves: it throws, or changes between calls. */
@UtilityClass
public class ValueTextTestData {

  /**
   * Returns a value whose {@code toString()} throws.
   *
   * @param failure what it throws
   * @return the value
   */
  public static Object throwing(RuntimeException failure) {
    return new Object() {
      @Override
      public String toString() {
        throw failure;
      }
    };
  }

  /**
   * Returns a value whose {@code toString()} returns the first text, then the second ever after.
   *
   * @param first the text of the first call
   * @param later the text of every later call
   * @return the value
   */
  public static Object changing(String first, String later) {
    var texts = textsOf(first, later);
    return new Object() {
      @Override
      public String toString() {
        return texts.hasNext() ? texts.next() : later;
      }
    };
  }

  /**
   * Returns a number whose {@code toString()} returns the first text, then the second ever after.
   *
   * @param first the text of the first call
   * @param later the text of every later call
   * @return the number
   */
  public static Number changingNumber(String first, String later) {
    var texts = textsOf(first, later);
    return new Number() {
      @Override
      public int intValue() {
        return 0;
      }

      @Override
      public long longValue() {
        return 0;
      }

      @Override
      public float floatValue() {
        return 0;
      }

      @Override
      public double doubleValue() {
        return 0;
      }

      @Override
      public String toString() {
        return texts.hasNext() ? texts.next() : later;
      }
    };
  }

  /** Returns the texts in order. */
  private static Iterator<String> textsOf(String first, String later) {
    return List.of(first, later).iterator();
  }
}
