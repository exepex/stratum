/**
 * The resilience module's incoming part: a limit on the requests each configured Spring MVC route
 * runs at once, which refuses the rest at once with {@code SX-RESILIENCE-0001}, unless {@code
 * stratum.resilience.enabled=false}.
 */
@NullMarked
package com.exepex.stratum.resilience;

import org.jspecify.annotations.NullMarked;
