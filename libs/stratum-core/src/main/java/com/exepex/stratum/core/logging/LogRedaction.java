package com.exepex.stratum.core.logging;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * Present as a bean exactly while the logging starter's redaction runs over every log line, so that
 * another module may log a value that only that redaction makes safe, such as a called URL.
 */
@StratumInternal
public record LogRedaction() {}
