package com.exepex.stratum.data.it.pools.batch;

import org.springframework.data.annotation.Id;
import org.springframework.data.keyvalue.annotation.KeySpace;

/**
 * A note kept in memory by Spring Data's map repositories, not by JPA.
 *
 * @param id the note's id
 * @param text its text
 */
@KeySpace("cachedNotes")
public record CachedNote(@Id String id, String text) {}
