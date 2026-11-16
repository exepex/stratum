package com.exepex.stratum.data.it.pools;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * A note, whose id Hibernate makes itself, so a save in a transaction writes the row only at the
 * commit-time flush.
 */
@Entity
@Table(name = "notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Note {

  /** The note's id, made by Hibernate. */
  @Id @GeneratedValue private @Nullable UUID id;

  /** The note's text. */
  @Setter private @Nullable String text;
}
