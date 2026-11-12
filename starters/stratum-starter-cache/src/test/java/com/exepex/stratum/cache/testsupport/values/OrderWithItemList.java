package com.exepex.stratum.cache.testsupport.values;

import java.util.List;

/**
 * The order after a release changed the type of a field, so old entries no longer fit.
 *
 * @param id the order's identifier
 * @param quantity the changed field, once a number
 */
public record OrderWithItemList(String id, List<String> quantity) {}
