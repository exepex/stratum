package com.exepex.stratum.data.it.failures;

import org.springframework.data.jpa.repository.JpaRepository;

/** Stores accounts through Spring Data JPA, which translates Hibernate's errors. */
public interface AccountRepository extends JpaRepository<Account, Long> {}
