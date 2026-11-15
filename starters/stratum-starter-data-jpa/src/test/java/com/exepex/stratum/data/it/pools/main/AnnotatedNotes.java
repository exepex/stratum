package com.exepex.stratum.data.it.pools.main;

import com.exepex.stratum.data.UseDataSource;
import com.exepex.stratum.data.it.pools.Note;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Notes in a package no pool maps, which name the pool batch themselves. */
@UseDataSource("batch")
public interface AnnotatedNotes extends JpaRepository<Note, UUID> {}
