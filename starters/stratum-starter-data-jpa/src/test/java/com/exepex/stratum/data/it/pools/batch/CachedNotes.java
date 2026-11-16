package com.exepex.stratum.data.it.pools.batch;

import org.springframework.data.keyvalue.repository.KeyValueRepository;

/** Notes in memory, in the package the pool batch maps, which no pool advice may reach. */
public interface CachedNotes extends KeyValueRepository<CachedNote, String> {}
