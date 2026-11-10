package com.exepex.stratum.httpclient;

/**
 * Changes each call of every Stratum client before it is sent, as a bean. Every such bean runs
 * before the client's own interceptors, in {@code @Order} order. For a client that cannot name
 * interceptors, such as a generated one, check {@link OutgoingRequest#client()}. A bean that is
 * also named in {@code @StratumClient(interceptors = ...)} still runs once per attempt.
 */
@FunctionalInterface
public interface GlobalClientInterceptor extends ClientInterceptor {}
