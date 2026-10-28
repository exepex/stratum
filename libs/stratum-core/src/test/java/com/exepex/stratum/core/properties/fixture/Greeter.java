package com.exepex.stratum.core.properties.fixture;

/**
 * The bean the test module builds from its settings.
 *
 * @param settings the settings the module resolved and uses
 */
public record Greeter(GreetingProperties settings) {}
