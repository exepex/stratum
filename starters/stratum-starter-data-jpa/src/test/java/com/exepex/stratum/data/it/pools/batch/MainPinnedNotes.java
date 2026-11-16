package com.exepex.stratum.data.it.pools.batch;

import com.exepex.stratum.data.UseDataSource;
import com.exepex.stratum.data.it.pools.Note;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Notes in the package the pool batch maps, which name main themselves, and so stay on main. */
@UseDataSource("main")
public interface MainPinnedNotes extends JpaRepository<Note, UUID> {}
