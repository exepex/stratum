package com.exepex.stratum.core.error;

/** How serious a reported problem is. It decides whether a caller must act or may carry on. */
public enum Severity {

  /** The operation failed and did not do what was asked. */
  ERROR,

  /** The operation completed, but something needs attention, such as a deprecated setting. */
  WARNING
}
