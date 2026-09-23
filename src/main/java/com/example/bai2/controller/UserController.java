package com.example.bai2.controller;

import com.example.bai2.domain.User;
import com.example.bai2.exception.InvalidUserIdException;
import com.example.bai2.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<User> getUser(@PathVariable String userId) {
        User user = userService.getUserById(userId);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(user);
    }

    @ExceptionHandler(InvalidUserIdException.class)
    public ResponseEntity<Map<String, String>> handleInvalidUserId(InvalidUserIdException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
