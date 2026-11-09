/**
 * Masks secrets and personal data in text, by key name and by what the text looks like. The logging
 * starter masks log lines with these rules, and the observability starter masks the query of each
 * HTTP client call's URL on its span.
 */
@NullMarked
package com.exepex.stratum.redaction;

import org.jspecify.annotations.NullMarked;
