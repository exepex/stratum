package com.exepex.stratum.resilience.it;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.threads.virtual.enabled=true")
@DisplayName("On virtual threads, a route limited to 5 runs at most 5 requests at once")
class RouteLimitVirtualThreadsIntegrationTest extends RouteLimitIntegrationTest {}
