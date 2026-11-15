/**
 * Puts JPA repositories on named pools: the mapping from repository to pool, the advice that keeps
 * each transaction on one pool, and the startup checks.
 */
@NullMarked
package com.exepex.stratum.datajpa.routing;

import org.jspecify.annotations.NullMarked;
