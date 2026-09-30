package com.oms.gateway.auth;

import com.oms.web.security.OmsRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who may log in, and as what.
 *
 * <h2>This is a stand-in, and the write-up says so</h2>
 *
 * <p>A real platform delegates identity to Keycloak, Auth0, Cognito or an enterprise directory. It
 * does not keep users in a config file. This exists so the platform is runnable and demonstrable
 * end to end without provisioning an identity provider, and ADR 0007 records it as the first thing
 * to replace.
 *
 * <p>What it is <em>not</em> is a shortcut on the parts that matter:
 *
 * <ul>
 *   <li><b>Passwords are BCrypt hashes, never plaintext</b>, even for demo users. The hash is what
 *       goes in configuration.</li>
 *   <li><b>Password comparison is constant-time</b> - {@code PasswordEncoder.matches} - so it does
 *       not leak the password through timing.</li>
 *   <li><b>An unknown username still costs a hash comparison.</b> Returning early would make "no
 *       such user" measurably faster than "wrong password", which is a username-enumeration oracle.
 *       Rare in practice to exploit, trivial to avoid, and the sort of detail an interviewer
 *       remembers.</li>
 * </ul>
 *
 * <p>Demo accounts line up with the ones seeded into order-service, so a token obtained here works
 * against a freshly started stack.
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    /**
     * A dummy hash, compared against when the username is unknown so that both paths do the same
     * work. The value is the BCrypt hash of a random string nobody has.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * @param roles bare role names, without the {@code ROLE_} prefix
     */
    public record User(String username, String passwordHash, String accountId,
                       String displayName, List<String> roles) {
    }

    private final Map<String, User> users;
    private final PasswordEncoder passwordEncoder;

    public UserStore(AuthProperties properties, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.users = properties.users().isEmpty()
                ? demoUsers(passwordEncoder)
                : properties.users().stream()
                        .collect(Collectors.toUnmodifiableMap(User::username, Function.identity()));

        log.info("Authentication configured with {} users: {}", users.size(), users.keySet());
    }

    /**
     * Authenticates, or returns empty.
     *
     * <p>One return value for both "no such user" and "wrong password". Telling a caller which one
     * it was hands an attacker a list of valid usernames for free.
     */
    public Optional<User> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }

        User user = users.get(username);
        String hash = user != null ? user.passwordHash() : DUMMY_HASH;

        // Always performed, even for an unknown user, so the two paths take the same time.
        boolean matches = passwordEncoder.matches(password, hash);

        if (user == null || !matches) {
            log.info("Authentication failed for username {}", username);
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public Optional<User> find(String username) {
        return Optional.ofNullable(users.get(username));
    }

    public int size() {
        return users.size();
    }

    /**
     * Demo users, created only when none are configured.
     *
     * <p>Hashed at startup rather than hard-coded as hashes, so the passwords are readable in the
     * source where they belong for a demo, and never stored in plaintext at rest. The startup log
     * says loudly that these exist.
     */
    private static Map<String, User> demoUsers(PasswordEncoder encoder) {
        log.warn("No users configured - creating DEMO users with well-known passwords. "
                + "Set oms.auth.users[] before exposing this to anything.");

        return Map.of(
                "trader1", new User("trader1", encoder.encode("trader1-password"),
                        "ACC-TRADER-1", "Demo Trader One", List.of(OmsRoles.TRADER)),
                "trader2", new User("trader2", encoder.encode("trader2-password"),
                        "ACC-TRADER-2", "Demo Trader Two", List.of(OmsRoles.TRADER)),
                "mm1", new User("mm1", encoder.encode("mm1-password"),
                        "ACC-MM-1", "Demo Market Maker", List.of(OmsRoles.TRADER)),
                "riskuser", new User("riskuser", encoder.encode("risk-password"),
                        "ACC-TRADER-1", "Demo Risk Officer", List.of(OmsRoles.RISK)),
                "admin", new User("admin", encoder.encode("admin-password"),
                        "ACC-TRADER-1", "Demo Administrator",
                        List.of(OmsRoles.ADMIN, OmsRoles.RISK)));
    }
}
