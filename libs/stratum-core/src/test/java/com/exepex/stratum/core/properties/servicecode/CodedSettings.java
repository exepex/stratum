package com.exepex.stratum.core.properties.servicecode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings checked by a constraint that is not public.
 *
 * @param code a code of at most 3 characters
 */
@ConfigurationProperties("stratum.coded")
public record CodedSettings(@ShortCode(region = "us", format = String.class) String code) {}
