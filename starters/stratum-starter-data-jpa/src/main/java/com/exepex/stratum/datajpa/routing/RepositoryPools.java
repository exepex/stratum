package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.DataConstants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The pool of each mapped JPA repository, with what mapped it: each pool's packages, from {@code
 * stratum.data.sources.<name>.packages}, and its interfaces that carry {@code @UseDataSource}.
 *
 * @param pools each mapped repository interface and its pool; an interface missing here follows its
 *     transaction's pool, else {@code main}
 * @param packages each pool's mapped packages, as set
 * @param annotated each pool's repository interfaces that name it in {@code @UseDataSource}
 */
@StratumInternal
@Builder(access = AccessLevel.PACKAGE)
public record RepositoryPools(
    Map<Class<?>, String> pools,
    Map<String, List<String>> packages,
    Map<String, List<String>> annotated) {

  /** Copies the maps, so that the record cannot change. */
  public RepositoryPools {
    pools = Map.copyOf(pools);
    packages = Map.copyOf(packages);
    annotated = Map.copyOf(annotated);
  }

  /**
   * Returns whether nothing is mapped: no pool has packages and no repository carries
   * {@code @UseDataSource}. Routing is then left out.
   *
   * @return whether there is no mapping
   */
  public boolean isEmpty() {
    return packages.isEmpty() && annotated.isEmpty();
  }

  /**
   * Returns a repository's pool.
   *
   * @param repository the repository interface
   * @return the pool's name, or {@code null} if the repository is not mapped
   */
  public @Nullable String poolOf(Class<?> repository) {
    return pools.get(repository);
  }

  /**
   * Returns the named pools that repositories are mapped to, which the routing data source routes
   * to beside {@code main}.
   *
   * @return the pools' names, in alphabetical order
   */
  public Set<String> targets() {
    var targets = new TreeSet<String>();
    Stream.concat(packages.keySet().stream(), annotated.keySet().stream()).forEach(targets::add);
    targets.remove(DataConstants.MAIN_POOL);
    return targets;
  }
}
