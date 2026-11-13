/**
 * The data module's JDBC boundary, which every data starter shares: clean database errors,
 * statement limits, and safe defaults for Spring Boot's connection pool and transactions, unless
 * {@code stratum.data.enabled=false}.
 */
@NullMarked
package com.exepex.stratum.data;

import org.jspecify.annotations.NullMarked;
