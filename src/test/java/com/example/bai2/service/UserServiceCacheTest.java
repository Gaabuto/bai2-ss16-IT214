package com.example.bai2.service;

import com.example.bai2.config.CacheConfig;
import com.example.bai2.domain.User;
import com.example.bai2.exception.InvalidUserIdException;
import com.example.bai2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring Boot 4 da xoa @MockBean -> dung @MockitoBean (tuong duong, thuoc spring-test).
 */
@SpringBootTest
class UserServiceCacheTest {

    @Autowired
    private UserService userService;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private UserRepository userRepository;

    private Cache usersCache;

    @BeforeEach
    void setUp() {
        usersCache = cacheManager.getCache(CacheConfig.USERS_CACHE);
        usersCache.clear();
    }

    @Test
    void userServiceIsWrappedByAopProxy() {
        assertThat(AopUtils.isAopProxy(userService)).isTrue();
    }

    @Test
    void firstCallMissesCache_secondCallHitsCache() {
        User user = new User("U001", "Nguyen Van An", "an@fintech.vn", "0901000001");
        when(userRepository.findById("U001")).thenReturn(Optional.of(user));

        User first = userService.getUserById("U001");   // cache miss -> DB
        User second = userService.getUserById("U001");  // cache hit  -> khong cham DB

        assertThat(first).isSameAs(user);
        assertThat(second).isSameAs(user);
        verify(userRepository, times(1)).findById("U001");
        assertThat(usersCache.get("U001", User.class)).isSameAs(user);
    }

    @Test
    void differentKeysAreCachedSeparately() {
        when(userRepository.findById("U001")).thenReturn(Optional.of(new User("U001", "A", null, null)));
        when(userRepository.findById("U002")).thenReturn(Optional.of(new User("U002", "B", null, null)));

        userService.getUserById("U001");
        userService.getUserById("U002");
        userService.getUserById("U001");
        userService.getUserById("U002");

        verify(userRepository, times(1)).findById("U001");
        verify(userRepository, times(1)).findById("U002");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void invalidUserIdFailsFastWithoutTouchingDbOrCache(String userId) {
        assertThatThrownBy(() -> userService.getUserById(userId))
                .isInstanceOf(InvalidUserIdException.class);

        verify(userRepository, never()).findById(any());
        assertThat(nativeCacheSize()).isZero();
    }

    @Test
    void nullResultIsNotCached() {
        when(userRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThat(userService.getUserById("UNKNOWN")).isNull();
        assertThat(userService.getUserById("UNKNOWN")).isNull();

        // Khong cache null -> moi lan deu hoi lai DB
        verify(userRepository, times(2)).findById("UNKNOWN");
        assertThat(usersCache.get("UNKNOWN")).isNull();
    }

    @Test
    void userCreatedAfterMissIsVisibleImmediately() {
        when(userRepository.findById("U009")).thenReturn(Optional.empty());
        assertThat(userService.getUserById("U009")).isNull();

        User created = new User("U009", "New User", null, null);
        when(userRepository.findById("U009")).thenReturn(Optional.of(created));

        // Neu null bi cache thi o day van tra ve null (stale) du user da ton tai
        assertThat(userService.getUserById("U009")).isSameAs(created);
    }

    private int nativeCacheSize() {
        return ((ConcurrentMapCache) usersCache).getNativeCache().size();
    }
}
