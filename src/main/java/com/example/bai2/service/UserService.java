package com.example.bai2.service;

import com.example.bai2.config.CacheConfig;
import com.example.bai2.domain.User;
import com.example.bai2.exception.InvalidUserIdException;
import com.example.bai2.repository.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * condition: userId null/rong -> bo qua cache hoan toan (khong tao key rac),
     *            method van duoc goi va validateUserId() nem loi fail-fast.
     * unless   : ket qua null (user khong ton tai) -> khong luu vao cache.
     */
    @Cacheable(
            value = CacheConfig.USERS_CACHE,
            key = "#userId",
            condition = "#userId != null && !#userId.isBlank()",
            unless = "#result == null")
    public User getUserById(String userId) {
        validateUserId(userId);
        System.out.println(">>> Truy vấn Database cho userId: " + userId);
        return userRepository.findById(userId).orElse(null);
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new InvalidUserIdException("userId không được null hoặc rỗng");
        }
    }
}
