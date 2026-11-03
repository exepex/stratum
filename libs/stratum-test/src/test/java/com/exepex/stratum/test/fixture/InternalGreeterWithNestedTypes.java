package com.exepex.stratum.test.fixture;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * Breaks the rules: an internal type whose public nested type has no label of its own, beside
 * nested types that need none.
 */
@StratumInternal
public class InternalGreeterWithNestedTypes {

  /** A public nested type a service can reach, labelled only through the class around it. */
  public static class Exposed {}

  /** A labelled public nested type. */
  @StratumInternal
  public static class Labelled {}

  /** A package-private nested type, which no service can reach. */
  static class PackageVisible {}

  /** A private nested type, which no service can reach. */
  private static class Hidden {}
}
