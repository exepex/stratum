package com.exepex.stratum.data.it.pools.batch;

import com.exepex.stratum.data.it.pools.Note;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Notes in the package the pool batch maps. */
public interface BatchNotes extends JpaRepository<Note, UUID> {

  /**
   * Waits on the database for three seconds, longer than the tests' transaction limit.
   *
   * @return the number of rows the wait returns, 1
   */
  @Query(value = "SELECT count(*) FROM pg_sleep(3)", nativeQuery = true)
  long sleepThreeSeconds();

  /**
   * Saves a note in a transaction of its own, whatever transaction the caller is in.
   *
   * @param note the note
   * @return the saved note
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  default Note saveInOwnTransaction(Note note) {
    return save(note);
  }
}
