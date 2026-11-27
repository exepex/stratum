package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * Marks a failure with an error code that is answered without a log line while its code has a
 * status in {@code stratum.web.error.statuses}, such as a request a Stratum module refused on
 * purpose; the request log line still records its status. With no status mapped, it is logged as
 * any other failure, so the 500 the caller gets is explained.
 */
@StratumInternal
public interface UnloggedFailure {}
