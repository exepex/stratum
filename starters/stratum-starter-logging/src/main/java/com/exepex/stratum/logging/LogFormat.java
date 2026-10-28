package com.exepex.stratum.logging;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** How console log lines are written. Every format except {@link #TEXT} is one JSON object. */
@Getter
@RequiredArgsConstructor
public enum LogFormat {

  /** Elastic Common Schema, the default: the same field names in every service. */
  ECS("ecs"),

  /** The Logstash JSON format. */
  LOGSTASH("logstash"),

  /** The Graylog Extended Log Format. */
  GELF("gelf"),

  /** Plain text for people to read on a laptop. It is not redacted. */
  TEXT(null);

  /** Spring Boot's name for the format; {@code null} for plain text. */
  private final @Nullable String structuredFormat;
}
