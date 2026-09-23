# Báo cáo phân tích lỗi: `@Cacheable` không hoạt động (thiếu `@EnableCaching`)

**Hệ thống:** API tra cứu thông tin người dùng (Fintech, ~10.000 request/giây)
**Công nghệ:** Spring Boot 4.1.1, Spring Cache, Spring Data JPA, H2 (giả lập DB), JUnit 5 + Mockito

---

## 1. Hiện tượng

- Đã gắn `@Cacheable(value = "users", key = "#userId")` lên `UserService.getUserById()`.
- Nhưng **mọi request** đều in ra `>>> Truy vấn Database cho userId: ...` và thời gian phản hồi vẫn ~200ms, thay vì < 20ms khi cache hit.

## 2. Nguyên nhân (Yêu cầu a)

### 2.1. Annotation bị thiếu

File cấu hình chính (`Application.java`) **thiếu `@EnableCaching`**. `@SpringBootApplication` không tự bật cơ chế cache; thêm `spring-boot-starter-cache` vào classpath cũng không đủ.

### 2.2. Tại sao chỉ có `@Cacheable` là chưa đủ: cơ chế AOP Proxy và CacheInterceptor

`@Cacheable` chỉ là **metadata**, tức một cái nhãn dán lên method. Bản thân nó không chứa logic nào. Phải có thành phần khác đọc nhãn đó và chèn logic cache vào lời gọi method. Đó là việc của `@EnableCaching`:

```
@EnableCaching
   └─ @Import(CachingConfigurationSelector)
        ├─ AutoProxyRegistrar          → đăng ký InfrastructureAdvisorAutoProxyCreator (một BeanPostProcessor)
        └─ ProxyCachingConfiguration   → đăng ký:
             ├─ AnnotationCacheOperationSource  (đọc @Cacheable/@CachePut/@CacheEvict)
             ├─ CacheInterceptor               (logic: tra cache → gọi method → lưu cache)
             └─ BeanFactoryCacheOperationSourceAdvisor (ghép 2 thành phần trên thành Advisor)
```

Khi context khởi động, `InfrastructureAdvisorAutoProxyCreator` duyệt từng bean. Bean nào có method khớp với advisor (có `@Cacheable`) sẽ bị **bọc trong một proxy** (CGLIB subclass). Bean khác inject `UserService` thì thực chất nhận được proxy:

```
Controller ──► [Proxy UserService] ──► CacheInterceptor
                                         1. Tính key từ SpEL "#userId"
                                         2. cache.get(key) → có: trả về luôn (HIT, không gọi method thật)
                                         3. không có: gọi UserService thật → DB (MISS)
                                         4. cache.put(key, result)
```

**Khi thiếu `@EnableCaching`:** không có `CacheInterceptor`, không có advisor, không có auto-proxy creator. `UserService` được đăng ký là **object thường**, lời gọi đi thẳng vào method và `@Cacheable` bị bỏ qua hoàn toàn. Spring **không báo lỗi hay cảnh báo gì**, nên lỗi này khó phát hiện.

Spring Boot cũng không tự bù vào: `CacheAutoConfiguration` có điều kiện `@ConditionalOnBean(CacheAspectSupport.class)`. Bean `CacheAspectSupport` (chính là `CacheInterceptor`) chỉ tồn tại khi đã có `@EnableCaching`. Vì vậy thiếu annotation này thì Boot cũng không cấu hình `CacheManager`.

### 2.3. Vì sao mỗi lần gọi `getUserById` vẫn truy xuất Database

Vì `UserService` không phải proxy, không có thành phần nào tra cache trước khi chạy thân method. Mỗi lời gọi đều chạy `userRepository.findById(userId)`. Cache "users" thậm chí chưa bao giờ được tạo.

Test `MissingEnableCachingBugTest` tái hiện đúng lỗi này. Context có `UserService` với `@Cacheable` và có cả `CacheManager`, nhưng không có `@EnableCaching`. Kết quả:
- `AopUtils.isAopProxy(userService) == false`
- gọi 3 lần thì `verify(repo, times(3)).findById("U001")`, tức DB bị gọi 3 lần.

> **Lỗi liên quan cần tránh (self-invocation):** kể cả khi đã có `@EnableCaching`, nếu một method khác *trong cùng class* gọi `this.getUserById(...)` thì lời gọi không đi qua proxy và cache cũng không hoạt động. Luôn gọi method cacheable từ bean khác.

## 3. Cách sửa (Yêu cầu b)

### 3.1. Bật caching và cấu hình `CacheManager`: `config/CacheConfig.java`

Tách thành lớp `@Configuration` riêng để `Application.java` gọn và dễ thay đổi backend cache (ví dụ chuyển sang Redis) sau này:

```java
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String USERS_CACHE = "users";

    @Bean
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(USERS_CACHE);
        cacheManager.setAllowNullValues(false);   // không cho lưu null vào cache
        return cacheManager;
    }
}
```

`ConcurrentMapCacheManager` phù hợp cho một instance hoặc môi trường demo. Với 10.000 req/s chạy nhiều instance, nên dùng Redis (`RedisCacheManager`) để các instance dùng chung cache, kèm TTL. Xem mục 5.3.

### 3.2. `UserService` có kiểm tra tham số và điều kiện cache

```java
@Cacheable(
        value = CacheConfig.USERS_CACHE,
        key = "#userId",
        condition = "#userId != null && !#userId.isBlank()",   // không tạo key rác
        unless = "#result == null")                            // không cache kết quả null
public User getUserById(String userId) {
    validateUserId(userId);                                    // fail-fast
    System.out.println(">>> Truy vấn Database cho userId: " + userId);
    return userRepository.findById(userId).orElse(null);
}

private void validateUserId(String userId) {
    if (userId == null || userId.isBlank()) {
        throw new InvalidUserIdException("userId không được null hoặc rỗng");
    }
}
```

`UserController` (`GET /api/users/{userId}`) trả **404** khi user không tồn tại và **400** khi gặp `InvalidUserIdException`.

## 4. Test case chứng minh cache hoạt động (Yêu cầu c)

`UserServiceCacheTest` dùng `@SpringBootTest` và mock `UserRepository`.

> **Lưu ý Spring Boot 4:** `@MockBean` đã bị **xóa** (deprecated từ Boot 3.4). Annotation thay thế tương đương là `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`). Cách dùng giống hệt: thay bean thật trong context bằng mock Mockito.

```java
@SpringBootTest
class UserServiceCacheTest {
    @Autowired UserService userService;
    @Autowired CacheManager cacheManager;
    @MockitoBean UserRepository userRepository;

    @BeforeEach
    void setUp() { cacheManager.getCache("users").clear(); }

    @Test
    void firstCallMissesCache_secondCallHitsCache() {
        User user = new User("U001", "Nguyen Van An", "an@fintech.vn", "0901000001");
        when(userRepository.findById("U001")).thenReturn(Optional.of(user));

        User first  = userService.getUserById("U001");   // cache MISS → DB
        User second = userService.getUserById("U001");   // cache HIT  → không chạm DB

        assertThat(second).isSameAs(user);
        verify(userRepository, times(1)).findById("U001"); // DB chỉ bị gọi 1 lần
        assertThat(cacheManager.getCache("users").get("U001", User.class)).isSameAs(user);
    }
}
```

**Toàn bộ test (11/11 PASS, `./gradlew test`):**

| Test | Kiểm tra |
|---|---|
| `userServiceIsWrappedByAopProxy` | Sau khi sửa, `UserService` là AOP proxy |
| `firstCallMissesCache_secondCallHitsCache` | Lần 1 miss (DB 1 lần), lần 2 hit (DB không bị gọi thêm) |
| `differentKeysAreCachedSeparately` | Mỗi `userId` là một entry riêng |
| `invalidUserIdFailsFastWithoutTouchingDbOrCache` ×4 | `null`, `""`, `"   "`, `"\t"` → `InvalidUserIdException`, DB không bị gọi, cache vẫn rỗng |
| `nullResultIsNotCached` | User không tồn tại → gọi 2 lần thì DB bị hỏi 2 lần, cache không có entry |
| `userCreatedAfterMissIsVisibleImmediately` | User mới tạo sau lần miss được trả về ngay (không kẹt giá trị null cũ) |
| `MissingEnableCachingBugTest` | Tái hiện lỗi gốc: không có proxy, DB bị gọi mỗi lần |
| `Bai2ApplicationTests.contextLoads` | Context khởi động được |

**Chạy thực tế** (`./gradlew bootRun`, H2 có sẵn U001–U003, gọi `curl`):

| Request | Kết quả | Log "Truy vấn Database" |
|---|---|---|
| `/api/users/U001` × 4 | 200 | **1 lần** (chỉ lần đầu) |
| `/api/users/NOPE` × 2 | 404 | 2 lần (null không bị cache) |
| `/api/users/%20` (blank) | 400 | 0 lần (fail-fast) |

## 5. Đề xuất xử lý tình huống đặc biệt (Yêu cầu d)

### 5.1. `userId` null hoặc rỗng

**Vấn đề:**
- `key = "#userId"` với `userId = null` khiến Spring ném `IllegalArgumentException: Null key returned for cache operation`, một lỗi kỹ thuật khó hiểu với client.
- `userId = ""` hoặc `"   "` tạo ra **key rác** trong cache. Nếu kẻ tấn công gửi hàng loạt giá trị rác, cache bị phình to (memory pollution).
- Query DB với id vô nghĩa là lãng phí tài nguyên.

**Giải pháp gồm 2 lớp:**
1. `condition = "#userId != null && !#userId.isBlank()"`: `condition` được đánh giá **trước** khi tra hoặc ghi cache. Nếu `false`, `CacheInterceptor` bỏ qua cache hoàn toàn, không tính key và không tạo entry.
2. `validateUserId()` ở đầu method **ném `InvalidUserIdException` ngay (fail-fast)** trước khi chạm DB. Controller map lỗi này thành HTTP 400 với thông báo rõ ràng.

Ở tầng Controller có thể thêm Bean Validation (`@NotBlank`, `@Pattern` cho định dạng id) để chặn còn sớm hơn. Validate trong service vẫn cần giữ vì service còn được gọi từ các nơi khác.

### 5.2. Phương thức trả về null: có nên cache không?

| | **Cache null** (negative caching) | **Không cache null** |
|---|---|---|
| Ưu điểm | Chống **cache penetration**: kẻ tấn công gửi liên tục id không tồn tại thì DB không bị dội | Không bao giờ trả dữ liệu sai. User vừa tạo xuất hiện ngay |
| Nhược điểm | **Dữ liệu cũ (stale):** user vừa đăng ký vẫn bị báo "không tồn tại" cho tới khi entry hết hạn. Tốn bộ nhớ cho các id rác | Mỗi request với id không tồn tại đều xuống DB |

**Khuyến nghị cho bài toán này: không cache null.** Với ứng dụng ngân hàng, trả "không tìm thấy" cho một user đã tồn tại (ví dụ khách vừa mở tài khoản) là lỗi nghiệp vụ nghiêm trọng. Rủi ro cache penetration nên xử lý ở lớp khác: rate limiting ở API Gateway, validate định dạng id, hoặc Bloom filter chứa các id hợp lệ.

**Cấu hình đã áp dụng (2 lớp bảo vệ):**
- `unless = "#result == null"` trên `@Cacheable`. `unless` được đánh giá **sau** khi method chạy, nên có thể dùng `#result`. Kết quả null sẽ không được `put` vào cache.
- `ConcurrentMapCacheManager.setAllowNullValues(false)` ở tầng `CacheManager`. Nếu sau này có ai quên `unless` ở method khác, việc lưu null sẽ **ném lỗi ngay** thay vì âm thầm cache null.

Nếu vẫn cần negative caching (hệ thống bị tấn công dò id), hãy cache null với **TTL rất ngắn** (ví dụ 30–60 giây) trong một cache riêng, và `@CacheEvict` entry đó khi tạo user mới.

### 5.3. Cấu hình tương đương khi chuyển sang Redis (production nhiều instance)

`disableCachingNullValues()` là API của Redis, tương đương với `setAllowNullValues(false)` ở trên:

```java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))   // tránh dữ liệu cũ tồn tại mãi
            .disableCachingNullValues();         // không lưu null
    return RedisCacheManager.builder(factory).cacheDefaults(config).build();
}
```

Kết hợp với `@CacheEvict(value = "users", key = "#user.id")` trên các method cập nhật hoặc xóa user để đảm bảo cache luôn nhất quán với DB.

## 6. Kết luận

| Hạng mục | Trước | Sau |
|---|---|---|
| `@EnableCaching` | Thiếu, không có proxy | Có trong `CacheConfig` |
| `CacheManager` | Không có | `ConcurrentMapCacheManager`, không cho phép null |
| Gọi lặp cùng `userId` | Lần nào cũng xuống DB | Chỉ lần đầu xuống DB |
| `userId` null/rỗng | Lỗi "Null key" hoặc key rác | `InvalidUserIdException` (400), không chạm DB/cache |
| User không tồn tại | (sẽ bị cache null) | Không cache, trả 404 |

Bài học: `@Cacheable`, `@Transactional`, `@Async`... đều hoạt động qua **AOP proxy**. Annotation trên method chỉ có tác dụng khi có `@Enable...` tương ứng để đăng ký interceptor, và khi lời gọi đi qua proxy.
