package com.exepex.stratum.data.it.pools.main;

import com.exepex.stratum.data.it.pools.Note;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Notes in a package no pool maps, so on the transaction's pool, else main. */
public interface MainNotes extends JpaRepository<Note, UUID> {}
