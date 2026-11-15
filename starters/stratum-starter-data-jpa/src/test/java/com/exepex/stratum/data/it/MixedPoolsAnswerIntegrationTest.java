package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.it.pools.Note;
import com.exepex.stratum.data.it.pools.PoolsApplication;
import com.exepex.stratum.data.it.pools.batch.BatchNotes;
import com.exepex.stratum.data.it.pools.main.MainNotes;
import com.exepex.stratum.data.testsupport.PoolDatabases;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.WebTestSupport;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = {
      PoolsApplication.class,
      PostgresContainerConfiguration.class,
      MixedPoolsAnswerIntegrationTest.MixedNotesController.class
    },
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      PoolDatabases.MAPPING,
      PoolDatabases.SQL_INIT,
      PoolDatabases.SCHEMA,
      "spring.main.web-application-type=servlet"
    })
@ContextConfiguration(initializers = PoolDatabases.BatchDatabase.class)
@DisplayName("A request whose transaction reached two pools is answered without naming a class")
class MixedPoolsAnswerIntegrationTest {

  /** The path of the endpoint that saves on main, then on batch, in one transaction. */
  private static final String MIXED_NOTES = "/notes/mixed";

  @LocalServerPort private int port;

  @Test
  @DisplayName("the body carries SX-DATA-1008 and names no package, repository or entity")
  void namesNoClass() throws IOException, InterruptedException {
    var response = WebTestSupport.getFromService(port, MIXED_NOTES);

    assertThat(response.body())
        .contains(DataErrorCode.POOL_MIXED.getCode())
        .doesNotContain("com.exepex")
        .doesNotContain(BatchNotes.class.getSimpleName())
        .doesNotContain(MainNotes.class.getSimpleName())
        .doesNotContain(Note.class.getSimpleName());
  }

  /** Saves on main, then on batch, in one transaction, which Stratum stops. */
  @RestController
  @RequiredArgsConstructor
  static class MixedNotesController {

    /** The repository of main. */
    private final MainNotes mainNotes;

    /** The repository of batch. */
    private final BatchNotes batchNotes;

    /**
     * Saves a note on main, then one on batch, in one transaction.
     *
     * @return never returns normally
     */
    @GetMapping(MIXED_NOTES)
    @Transactional
    String saveOnBothPools() {
      mainNotes.save(new Note(null, DataConstants.MAIN_POOL));
      return String.valueOf(batchNotes.save(new Note(null, PoolDatabases.BATCH)).getId());
    }
  }
}
