/**
 * Limits how many lines each logger writes per second and samples DEBUG and INFO lines, deciding
 * before a line is built, so that a flood of lines cannot slow the service down.
 */
@NullMarked
package com.exepex.stratum.logging.volume;

import org.jspecify.annotations.NullMarked;
