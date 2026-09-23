package com.example.bai2.service;

import com.example.bai2.domain.User;
import com.example.bai2.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tai hien loi goc: co @Cacheable, co ca CacheManager, nhung THIEU @EnableCaching
 * -> UserService la object "tran" (khong co proxy) -> moi lan goi deu xuong DB.
 */
class MissingEnableCachingBugTest {

    @Configuration
    @Import(UserService.class)
    static class NoEnableCachingConfig {

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("users");
        }
    }

    @Test
    void withoutEnableCaching_everyCallHitsDatabase() {
        try (var ctx = new AnnotationConfigApplicationContext(NoEnableCachingConfig.class)) {
            UserService userService = ctx.getBean(UserService.class);
            UserRepository repo = ctx.getBean(UserRepository.class);
            when(repo.findById("U001")).thenReturn(Optional.of(new User("U001", "A", null, null)));

            userService.getUserById("U001");
            userService.getUserById("U001");
            userService.getUserById("U001");

            assertThat(AopUtils.isAopProxy(userService)).isFalse();
            verify(repo, times(3)).findById("U001");
        }
    }
}
