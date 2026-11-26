# Common Spring Boot Pitfalls & Fixes

## 1. @Transactional Self-Invocation (Most Common Bug)

**Problem**: Calling a `@Transactional` method from within the same class bypasses the proxy — no transaction starts.

```java
// BROKEN — createOrder() is called directly, no transaction
public void process() {
    createOrder(); // self-invocation, @Transactional ignored!
}

@Transactional
public void createOrder() { ... }
```

**Fix**: Inject self, or move the method to a separate `@Service`.

---

## 2. LazyInitializationException

**Problem**: Accessing a `@OneToMany` / `@ManyToMany` collection outside the transaction scope (e.g., in a controller).

**Fix options**:
- Use `@Transactional` in the service that loads the entity.
- Use `@EntityGraph` to eagerly load the association on specific queries.
- Map to a DTO *inside* the service — never expose entities to the controller.
- Use `spring.jpa.open-in-view=false` (should be disabled in production).

---

## 3. @Async Not Working

**Problem**: `@Async` on a method in the same class, or missing `@EnableAsync` on config.

**Fixes**:
- Add `@EnableAsync` to a `@Configuration` class.
- Move `@Async` methods to a separate bean (self-invocation problem, same as `@Transactional`).
- Configure a custom `ThreadPoolTaskExecutor` — default uses a `SimpleAsyncTaskExecutor` which creates threads unboundedly.

---

## 4. Bean Overriding / Circular Dependencies

**Problem**: Circular `@Autowired` dependencies cause `BeanCurrentlyInCreationException`.

**Fix**: Refactor to remove the cycle (usually a design smell — extract a third service). If unavoidable, use `@Lazy` on one injection point.

---

## 5. HikariCP Connection Pool Exhaustion

**Symptoms**: App hangs, `Connection is not available, request timed out` after 30s.

**Common causes**:
- Long-running transactions holding connections
- `@Transactional` scope too broad (wrapping external HTTP calls)
- Pool size too small for load

**Fix**: Keep transactions short and DB-only. Default pool size is 10 — tune `spring.datasource.hikari.maximumPoolSize` based on load. Never make HTTP calls inside a `@Transactional` method.

---

## 6. @ConfigurationProperties Not Binding

**Checklist**:
- [ ] `@EnableConfigurationProperties(MyProps.class)` or `@ConfigurationPropertiesScan` added?
- [ ] Class has a no-arg constructor or is a record?
- [ ] Property names match kebab-case (`my-property` in YAML, `myProperty` in code)?
- [ ] Annotated with `@Component` OR registered via `@EnableConfigurationProperties`?

---

## 7. Incorrect Rollback on Checked Exceptions

**Problem**: By default, `@Transactional` only rolls back on `RuntimeException`. Checked exceptions commit!

```java
@Transactional
public void doWork() throws IOException {
    repo.save(entity);
    throw new IOException("fail"); // transaction COMMITS — entity is saved!
}
```

**Fix**: `@Transactional(rollbackFor = Exception.class)` or convert to unchecked exception.

---

## 8. Jackson Serialization Issues

**Common problems**:
- `No serializer found` — missing no-arg constructor or getters on DTO
- Infinite recursion on bidirectional JPA relationships — use `@JsonIgnore` or `@JsonManagedReference`/`@JsonBackReference`
- Date/time as timestamp array — add `spring.jackson.serialization.write-dates-as-timestamps=false`

---

## 9. Spring Security CSRF in APIs

**Problem**: REST API returns 403 Forbidden on POST/PUT/DELETE with default Spring Security config.

**Fix**: Disable CSRF for stateless REST APIs (CSRF protects browser session-based apps, not token-authenticated APIs):
```java
http.csrf(csrf -> csrf.disable())
    .sessionManagement(s -> s.sessionCreationPolicy(STATELESS));
```

---

## 10. Profile-Specific Config Overrides

Loading order (later overrides earlier):
1. `application.properties`
2. `application-{profile}.properties`
3. Environment variables
4. Command-line args

Use `@Profile("!prod")` to exclude beans from production. Never hardcode `"dev"` profile checks in code.
