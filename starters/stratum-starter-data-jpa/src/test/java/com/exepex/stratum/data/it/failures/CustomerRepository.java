package com.exepex.stratum.data.it.failures;

import org.springframework.data.jpa.repository.JpaRepository;

/** Stores customers through Spring Data JPA, which translates Hibernate's errors. */
public interface CustomerRepository extends JpaRepository<Customer, Long> {}
