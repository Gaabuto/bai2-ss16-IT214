package com.example.bai2.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FIX: @EnableCaching dang ky CacheInterceptor + BeanPostProcessor tao AOP proxy
 * cho cac bean co @Cacheable. Thieu annotation nay thi @Cacheable chi la metadata "chet".
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String USERS_CACHE = "users";

    @Bean
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(USERS_CACHE);
        // Tuong duong RedisCacheConfiguration.disableCachingNullValues():
        // khong cho phep luu null vao cache (lop bao ve thu 2 ngoai "unless" o @Cacheable).
        cacheManager.setAllowNullValues(false);
        return cacheManager;
    }
}
