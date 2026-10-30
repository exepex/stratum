package com.exepex.stratum.test.fixture;

import org.springframework.stereotype.Component;

/** Breaks the rules: a component scan picks it up even while the module is off. */
@Component
public class UnswitchedGreeter {}
