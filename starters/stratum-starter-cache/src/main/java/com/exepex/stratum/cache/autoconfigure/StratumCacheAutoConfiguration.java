package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheAspectSupport;

/**
 * Switches Spring's caching on, so a service needs no {@code @EnableCaching}, unless {@code
 * stratum.cache.enabled=false} or the service or its framework already switches it on. Ordered
 * before Spring Boot's cache auto-configuration, which sets up a cache manager only once caching is
 * on.
 */
@StratumInternal
@AutoConfiguration(before = CacheAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
@ConditionalOnMissingBean(CacheAspectSupport.class)
@EnableCaching
public final class StratumCacheAutoConfiguration {}
