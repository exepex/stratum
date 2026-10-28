package com.exepex.stratum.core.properties.fixture;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of the test module, bound to {@code stratum.greeting.*}.
 *
 * @param message the text the module greets with. Defaults to {@code Hello}.
 * @param timeout how long the module waits for an answer. Defaults to 2 seconds.
 */
@Builder(toBuilder = true)
@Validated
@ConfigurationProperties(prefix = "stratum.greeting")
public record GreetingProperties(
    @DefaultValue("Hello") @NotBlank String message,
    @DefaultValue("2s") @NotNull Duration timeout) {}
