package com.oms.marketdata.api;

import com.oms.common.marketdata.QuoteSnapshot;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import com.oms.marketdata.config.SecurityConfig;
import com.oms.marketdata.quote.QuoteCache;
import com.oms.marketdata.reference.InstrumentService;
import com.oms.marketdata.simulator.TickGenerator;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.oms.common.error.NotFoundException.instrument;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The quote and instrument endpoints, with the service's real filter chain.
 *
 * <p>Reference data and quotes are {@code authenticated()} rather than role-restricted, which is
 * a deliberate asymmetry with book depth: a quote is public market information, and every role
 * legitimately needs it. What is <em>not</em> acceptable is serving it anonymously, because the
 * rate limiter keys on the account in the token.
 *
 * <p>The fallback order in {@code quote()} is the other thing worth pinning: the live simulator
 * state first, the Redis snapshot second. Reversing them would serve a cached price while a
 * fresher one existed in memory.
 */
@WebMvcTest({QuoteController.class, InstrumentController.class})
@Import({OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class, SecurityConfig.class})
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/oauth2/jwks")
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TickGenerator tickGenerator;
    @MockitoBean
    private QuoteCache quoteCache;
    @MockitoBean
    private InstrumentService instrumentService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("anonymous is refused: the rate limiter keys on the account in the token")
        void anonymousIsRefused() throws Exception {
            mockMvc.perform(get("/api/v1/quotes/HBL"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("every role can read a quote - it is public market information")
        void everyRoleCanReadQuotes() throws Exception {
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.of(snapshot()));

            for (String role : List.of(OmsRoles.TRADER, OmsRoles.RISK, OmsRoles.ADMIN)) {
                mockMvc.perform(get("/api/v1/quotes/HBL").with(caller("ACC-1", role)))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("reference data is readable by any authenticated caller")
        void referenceDataIsAuthenticatedOnly() throws Exception {
            when(instrumentService.findAll()).thenReturn(List.of(view()));

            mockMvc.perform(get("/api/v1/instruments"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/instruments").with(caller("ACC-1", OmsRoles.RISK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Nested
    @DisplayName("quote lookup")
    class QuoteLookup {

        @Test
        @DisplayName("live simulator state is preferred over the Redis snapshot")
        void liveStateWins() throws Exception {
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.of(snapshot()));

            mockMvc.perform(get("/api/v1/quotes/HBL").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sequence").value(42));

            verify(quoteCache, never())
                    .get(anyString());
        }

        @Test
        @DisplayName("the cache is the fallback when this instance holds no live state")
        void cacheIsTheFallback() throws Exception {
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.empty());
            when(quoteCache.get("HBL")).thenReturn(Optional.of(snapshot()));

            mockMvc.perform(get("/api/v1/quotes/HBL").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("HBL"));
        }

        @Test
        @DisplayName("neither source having the symbol is a 404 in the platform error contract")
        void unknownSymbolIs404() throws Exception {
            when(tickGenerator.liveSnapshot("NOPE")).thenReturn(Optional.empty());
            when(quoteCache.get("NOPE")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/quotes/NOPE").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INSTRUMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("the symbol is upper-cased, so hbl and HBL are one instrument")
        void symbolIsCaseInsensitive() throws Exception {
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.of(snapshot()));

            mockMvc.perform(get("/api/v1/quotes/hbl").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("HBL"));

            verify(tickGenerator).liveSnapshot("HBL");
        }

        @Test
        @DisplayName("mid and spread are derived for the client rather than left to it")
        void midAndSpreadAreDerived() throws Exception {
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.of(snapshot()));

            mockMvc.perform(get("/api/v1/quotes/HBL").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bid").value(172.4000))
                    .andExpect(jsonPath("$.ask").value(172.5000))
                    .andExpect(jsonPath("$.mid").value(172.4500))
                    .andExpect(jsonPath("$.spread").value(0.1000))
                    .andExpect(jsonPath("$.bidSize").value(1000))
                    .andExpect(jsonPath("$.asOf").exists());
        }

        @Test
        @DisplayName("a one-sided quote omits mid and spread instead of inventing them")
        void oneSidedQuoteOmitsDerivedFields() throws Exception {
            QuoteSnapshot oneSided = new QuoteSnapshot("HBL",
                    new BigDecimal("172.4000"), 1_000,
                    null, 0,
                    new BigDecimal("172.4500"), 200,
                    43L, Instant.parse("2026-09-28T09:15:30Z"));
            when(tickGenerator.liveSnapshot("HBL")).thenReturn(Optional.of(oneSided));

            mockMvc.perform(get("/api/v1/quotes/HBL").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bid").value(172.4000))
                    .andExpect(jsonPath("$.ask").doesNotExist())
                    .andExpect(jsonPath("$.mid")
                            // A mid computed from a missing ask would be a made-up price that a
                            // client cannot tell from a real one.
                            .doesNotExist())
                    .andExpect(jsonPath("$.spread").doesNotExist());
        }
    }

    @Nested
    @DisplayName("instrument lookup")
    class InstrumentLookup {

        @Test
        @DisplayName("a symbol lookup is upper-cased and returns the contract view")
        void lookupReturnsTheView() throws Exception {
            when(instrumentService.findBySymbol("HBL")).thenReturn(view());

            mockMvc.perform(get("/api/v1/instruments/hbl").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("HBL"))
                    .andExpect(jsonPath("$.lotSize").value(1))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("an unknown symbol is a 404, and the shared advice renders it")
        void unknownInstrumentIs404() throws Exception {
            when(instrumentService.findBySymbol("NOPE")).thenThrow(instrument("NOPE"));

            mockMvc.perform(get("/api/v1/instruments/NOPE").with(caller("ACC-1", OmsRoles.TRADER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INSTRUMENT_NOT_FOUND"))
                    .andExpect(jsonPath("$.path").value("/api/v1/instruments/NOPE"));
        }
    }

    private static QuoteSnapshot snapshot() {
        return new QuoteSnapshot("HBL",
                new BigDecimal("172.4000"), 1_000,
                new BigDecimal("172.5000"), 800,
                new BigDecimal("172.4500"), 200,
                42L, Instant.parse("2026-09-28T09:15:30Z"));
    }

    private static InstrumentView view() {
        return new InstrumentView("HBL", "Habib Bank Limited", "PK0001901014", "PKR",
                1L, 100L, new BigDecimal("10.00"), 1_724_500L, InstrumentStatus.ACTIVE);
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
