package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;

/** A service's Java configuration: up to 200 connections to each downstream service. */
public class LargePoolHttpClientSettings implements HttpClientPropertiesCustomizer {

  /** The pool size this configuration sets. */
  public static final int MAX_CONNECTIONS = 200;

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    return properties.toBuilder()
        .pool(properties.pool().toBuilder().maxConnections(MAX_CONNECTIONS).build())
        .build();
  }
}
