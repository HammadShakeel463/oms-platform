package com.oms.matching.api;

import com.oms.common.money.Ticks;
import com.oms.matching.book.BookSnapshot;
import com.oms.matching.config.SecurityConfig;
import com.oms.matching.engine.MatchingEngine;
import com.oms.web.OmsWebAutoConfiguration;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsRoles;
import com.oms.web.security.OmsSecurityAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The book depth endpoint and the engine's authorisation rules.
 *
 * <p>Book depth is {@code ADMIN}-only, and that is a domain decision rather than caution: a live
 * order book is other participants' resting intent. A trader who can read the whole book can see
 * exactly where the resting size is, which is information a venue does not hand out. The test
 * asserting that a {@code TRADER} gets 403 is therefore testing a business rule, not a config
 * line.
 */
@WebMvcTest(BookController.class)
@Import({OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class, SecurityConfig.class})
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/oauth2/jwks")
class BookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MatchingEngine engine;

    /** The real decoder would fetch a JWK set over HTTP at startup; jwt() bypasses decoding. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("no token is 401 in the platform error contract, not an empty body")
        void anonymousIsUnauthorised() throws Exception {
            mockMvc.perform(get("/api/v1/books/HBL"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("a TRADER is 403: the full book is other participants' resting intent")
        void traderCannotReadTheBook() throws Exception {
            mockMvc.perform(get("/api/v1/books/HBL").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("RISK is read-everything for positions but still not the book")
        void riskCannotReadTheBook() throws Exception {
            mockMvc.perform(get("/api/v1/books/HBL").with(caller("ACC-1", OmsRoles.RISK)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN can read it")
        void adminCanReadTheBook() throws Exception {
            when(engine.snapshot("HBL")).thenReturn(BookSnapshot.empty("HBL"));

            mockMvc.perform(get("/api/v1/books/HBL").with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("401 and 403 are not collapsed - one says authenticate, the other says do not bother")
        void unauthenticatedAndForbiddenAreDistinct() throws Exception {
            mockMvc.perform(get("/api/v1/books"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/books").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("depth rendering")
    class DepthRendering {

        @Test
        @DisplayName("ticks become decimals at the API boundary, exactly once")
        void ticksAreRenderedAsDecimals() throws Exception {
            when(engine.snapshot("HBL")).thenReturn(BookSnapshot.of("HBL", 42L,
                    1_724_000L, 1_725_000L,
                    List.of(new BookSnapshot.Level(1_724_000L, 800, 2),
                            new BookSnapshot.Level(1_723_000L, 300, 1)),
                    List.of(new BookSnapshot.Level(1_725_000L, 400, 1))));

            mockMvc.perform(get("/api/v1/books/HBL").with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("HBL"))
                    .andExpect(jsonPath("$.sequence").value(42))
                    .andExpect(jsonPath("$.bestBid").value(172.4000))
                    .andExpect(jsonPath("$.bestAsk").value(172.5000))
                    .andExpect(jsonPath("$.spread").value(0.1000))
                    .andExpect(jsonPath("$.bids.length()").value(2))
                    .andExpect(jsonPath("$.bids[0].price").value(172.4000))
                    .andExpect(jsonPath("$.bids[0].quantity").value(800))
                    .andExpect(jsonPath("$.bids[0].orderCount").value(2))
                    .andExpect(jsonPath("$.asks.length()").value(1));
        }

        @Test
        @DisplayName("the depth parameter truncates each side independently")
        void depthLimitsEachSide() throws Exception {
            when(engine.snapshot("HBL")).thenReturn(BookSnapshot.of("HBL", 1L,
                    1_724_000L, 1_725_000L,
                    List.of(new BookSnapshot.Level(1_724_000L, 100, 1),
                            new BookSnapshot.Level(1_723_000L, 100, 1),
                            new BookSnapshot.Level(1_722_000L, 100, 1)),
                    List.of(new BookSnapshot.Level(1_725_000L, 100, 1),
                            new BookSnapshot.Level(1_726_000L, 100, 1))));

            mockMvc.perform(get("/api/v1/books/HBL").param("depth", "2")
                            .with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bids.length()").value(2))
                    .andExpect(jsonPath("$.asks.length()").value(2));
        }

        @Test
        @DisplayName("/top is depth 1, whatever the book holds")
        void topIsDepthOne() throws Exception {
            when(engine.snapshot("HBL")).thenReturn(BookSnapshot.of("HBL", 1L,
                    1_724_000L, 1_725_000L,
                    List.of(new BookSnapshot.Level(1_724_000L, 100, 1),
                            new BookSnapshot.Level(1_723_000L, 100, 1)),
                    List.of(new BookSnapshot.Level(1_725_000L, 100, 1),
                            new BookSnapshot.Level(1_726_000L, 100, 1))));

            mockMvc.perform(get("/api/v1/books/HBL/top").with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bids.length()").value(1))
                    .andExpect(jsonPath("$.asks.length()").value(1));
        }

        @Test
        @DisplayName("an empty book renders as an empty book, not a 404 - the symbol is still valid")
        void emptyBookIsNotAnError() throws Exception {
            when(engine.snapshot("NEWCO")).thenReturn(BookSnapshot.empty("NEWCO"));

            mockMvc.perform(get("/api/v1/books/NEWCO").with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bids.length()").value(0))
                    .andExpect(jsonPath("$.asks.length()").value(0))
                    .andExpect(jsonPath("$.bestBid").doesNotExist())
                    .andExpect(jsonPath("$.spread").doesNotExist());
        }

        @Test
        @DisplayName("a depth outside 1..50 is rejected by validation, not silently clamped")
        void depthIsValidated() throws Exception {
            mockMvc.perform(get("/api/v1/books/HBL").param("depth", "0")
                            .with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            mockMvc.perform(get("/api/v1/books/HBL").param("depth", "500")
                            .with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the symbol list is served to an ADMIN")
        void symbolsAreListed() throws Exception {
            when(engine.symbols()).thenReturn(List.of("HBL", "ENGRO"));

            mockMvc.perform(get("/api/v1/books").with(caller("ACC-1", OmsRoles.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    @Test
    @DisplayName("NO_PRICE renders as an absent field, never as a sentinel number in JSON")
    void sentinelNeverReachesTheWire() throws Exception {
        when(engine.snapshot(anyString())).thenReturn(BookSnapshot.of("HBL", 3L,
                1_724_000L, Ticks.NO_PRICE,
                List.of(new BookSnapshot.Level(1_724_000L, 100, 1)),
                List.of()));

        mockMvc.perform(get("/api/v1/books/HBL").with(caller("ACC-1", OmsRoles.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid").value(172.4000))
                .andExpect(jsonPath("$.bestAsk")
                        // Long.MIN_VALUE as a price would be a catastrophic thing for a client
                        // to treat as a number.
                        .doesNotExist())
                .andExpect(jsonPath("$.spread").doesNotExist());
    }

    private static RequestPostProcessor caller(String accountId, String... roles) {
        return jwt().jwt(builder -> builder
                        .subject("hammad")
                        .claim(OmsClaims.ACCOUNT_ID, accountId)
                        .claim(OmsClaims.ROLES, List.of(roles)))
                .authorities(Arrays.stream(roles)
                        .map(role -> (GrantedAuthority)
                                new SimpleGrantedAuthority(OmsRoles.PREFIX + role))
                        .toList());
    }
}
