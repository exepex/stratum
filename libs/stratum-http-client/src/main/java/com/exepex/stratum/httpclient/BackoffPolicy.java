package com.exepex.stratum.httpclient;

/**
 * How the wait before each retry of a client grows. Under both, each wait is spread at random by up
 * to half its length, so that many callers do not retry at the same instant.
 */
public enum BackoffPolicy {

  /** Each wait is {@code multiplier} times the last, from {@code delay} up to {@code max-delay}. */
  EXPONENTIAL,

  /** Every wait is {@code delay}; {@code multiplier} and {@code max-delay} are ignored. */
  FIXED
}
