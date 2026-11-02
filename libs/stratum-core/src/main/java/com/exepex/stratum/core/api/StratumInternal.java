package com.exepex.stratum.core.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type that only Stratum's own modules use. A service must not use it: it can change or go
 * away in any release, without notice. A public Stratum type without this label is part of the API
 * that services rely on.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StratumInternal {}
