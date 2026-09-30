package com.oms.gateway.auth;

import com.oms.web.security.OmsRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserStoreTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static UserStore withUsers(UserStore.User... users) {
        return new UserStore(new AuthProperties(Duration.ofMinutes(15), List.of(users)), ENCODER);
    }

    private static UserStore.User user(String username, String password, String... roles) {
        return new UserStore.User(username, ENCODER.encode(password),
                "ACC-TRADER-1", "Test User", List.of(roles));
    }

    @Test
    @DisplayName("correct credentials authenticate")
    void correctCredentials() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        assertThat(store.authenticate("hammad", "secret"))
                .get()
                .satisfies(u -> assertThat(u.roles()).containsExactly(OmsRoles.TRADER));
    }

    @Test
    @DisplayName("a wrong password is refused")
    void wrongPassword() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        assertThat(store.authenticate("hammad", "Secret")).isEmpty();
        assertThat(store.authenticate("hammad", "")).isEmpty();
    }

    @Test
    @DisplayName("an unknown user is refused, and indistinguishably so")
    void unknownUser() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        // Same empty result for both failure modes: distinguishing them would hand an attacker a
        // list of valid usernames for free.
        assertThat(store.authenticate("nobody", "secret")).isEmpty();
        assertThat(store.authenticate("nobody", "wrong")).isEmpty();
    }

    @Test
    @DisplayName("null credentials are refused without an exception")
    void nullCredentials() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        assertThat(store.authenticate(null, "secret")).isEmpty();
        assertThat(store.authenticate("hammad", null)).isEmpty();
    }

    @Test
    @DisplayName("an unknown username still costs a hash comparison")
    void unknownUsernameStillHashes() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        // Timing assertions are inherently noisy, so this checks the ORDER of magnitude rather
        // than a threshold: a short-circuit return would be microseconds, while a BCrypt
        // comparison at cost 10 is milliseconds. Anything in the millisecond range means the
        // dummy-hash comparison happened.
        long unknown = timeOf(() -> store.authenticate("nobody", "secret"));
        long wrongPassword = timeOf(() -> store.authenticate("hammad", "wrong"));

        assertThat(unknown)
                .as("returning early for an unknown user is a username-enumeration oracle")
                .isGreaterThan(1_000_000L);   // > 1ms
        assertThat(wrongPassword).isGreaterThan(1_000_000L);
    }

    private static long timeOf(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    @Test
    @DisplayName("passwords are never stored in plaintext")
    void passwordsAreHashed() {
        UserStore store = withUsers(user("hammad", "secret", OmsRoles.TRADER));

        assertThat(store.find("hammad"))
                .get()
                .satisfies(u -> {
                    assertThat(u.passwordHash()).doesNotContain("secret");
                    assertThat(u.passwordHash()).startsWith("$2a$");
                });
    }

    @Test
    @DisplayName("demo users are created when none are configured")
    void demoUsersWhenUnconfigured() {
        UserStore store = new UserStore(
                new AuthProperties(Duration.ofMinutes(15), List.of()), ENCODER);

        assertThat(store.size()).isEqualTo(5);
        assertThat(store.authenticate("trader1", "trader1-password"))
                .get()
                .satisfies(u -> assertThat(u.accountId()).isEqualTo("ACC-TRADER-1"));
        assertThat(store.authenticate("riskuser", "risk-password"))
                .get()
                .satisfies(u -> assertThat(u.roles())
                        .as("the risk officer has RISK and NOT trader - separation of duties")
                        .containsExactly(OmsRoles.RISK));
        assertThat(store.authenticate("admin", "admin-password"))
                .get()
                .satisfies(u -> assertThat(u.roles())
                        .containsExactlyInAnyOrder(OmsRoles.ADMIN, OmsRoles.RISK));
    }
}
