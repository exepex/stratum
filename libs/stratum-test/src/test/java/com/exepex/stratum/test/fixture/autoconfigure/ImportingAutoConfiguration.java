package com.exepex.stratum.test.fixture.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Follows the rules: a configuration class with no method of its own, which imports another, as a
 * starter does that brings a shared library's configurations.
 */
@StratumInternal
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule("greeting")
@Import(GreetingAutoConfiguration.class)
public final class ImportingAutoConfiguration {}
