package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * Marks Stratum's data source that routes each connection to one of the pools. Its pools already
 * have the boundary, the limits and the checks, so the boundary, the TLS check and the pool check
 * skip it.
 */
@StratumInternal
public interface PoolRouter {}
