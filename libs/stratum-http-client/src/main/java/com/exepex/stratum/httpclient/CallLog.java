package com.exepex.stratum.httpclient;

/**
 * Which calls of a client write a log line. The line names the client, method, URI template,
 * outcome, attempts and duration, never headers or bodies.
 */
public enum CallLog {

  /** No call writes a line; the meters still count every call. */
  NONE,

  /** Each failed call writes one WARN line, after its retries; a success writes nothing. */
  ERRORS,

  /** Each failed call writes one WARN line, and each successful call one INFO line. */
  ALL
}
