package com.oms.position.api;

import com.oms.common.domain.Side;
import com.oms.position.domain.PositionEntity;
import com.oms.position.config.SecurityConfig;
import com.oms.position.service.PositionService;
import com.oms.web.OmsWebAutoConfiguration;
import com.oms.web.security.OmsClaims;
import com.oms.web.security.OmsRoles;
import com.oms.web.security.OmsSecurityAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice.
 *
 * <p>The assertions worth having here are not the happy path - they are the ones about
 * <b>incompleteness</b>. A P&amp;L endpoint that silently reports a smaller number because one
 * position had no mark is the bug that makes somebody think they are flat when they are not, and it
 * is invisible in a unit test of the arithmetic.
 */
@WebMvcTest(PositionController.class)
@Import({OmsWebAutoConfiguration.class, OmsSecurityAutoConfiguration.class, SecurityConfig.class})
@TestPropertySource(properties =
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/oauth2/jwks")
class PositionControllerTest {

    private static final String ACCOUNT = "ACC-TRADER-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PositionService positionService;

    /** Mocked: the jwt() post-processor bypasses decoding, so nothing should fetch a JWK set. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor caller(String accountId, String... roles) {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(builder -> builder
                        .subject("hammad")
                        .claim(OmsClaims.ACCOUNT_ID, accountId)
                        .claim(OmsClaims.ROLES, java.util.List.of(roles)))
                .authorities(java.util.Arrays.stream(roles)
                        .map(role -> (org.springframework.security.core.GrantedAuthority)
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(OmsRoles.PREFIX + role))
                        .toList());
    }

    private static RequestPostProcessor trader() {
        return caller(ACCOUNT, OmsRoles.TRADER);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClock {
        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-29T09:15:00Z"), ZoneOffset.UTC);
        }
    }

    private static PositionEntity longPosition(String symbol, long quantity, String cost) {
        PositionEntity position = new PositionEntity(ACCOUNT, symbol);
        position.applyFill(Side.BUY, new BigDecimal(cost), quantity);
        return position;
    }

    @Test
    @DisplayName("a marked position reports unrealised P&L and market value")
    void markedPosition() throws Exception {
        when(positionService.positionFor(ACCOUNT, "HBL"))
                .thenReturn(longPosition("HBL", 500, "170.0000"));
        when(positionService.markFor("HBL")).thenReturn(Optional.of(new BigDecimal("174.0000")));

        mockMvc.perform(get("/api/v1/positions/{symbol}", "HBL")
                        .with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netQuantity").value(500))
                .andExpect(jsonPath("$.averageCost").value(170.0))
                .andExpect(jsonPath("$.unrealisedPnl").value(2000.0))
                .andExpect(jsonPath("$.marketValue").value(87000.0))
                .andExpect(jsonPath("$.markPrice").value(174.0));
    }

    @Test
    @DisplayName("an unmarked position reports null unrealised, not zero")
    void unmarkedPositionReportsNull() throws Exception {
        when(positionService.positionFor(ACCOUNT, "HBL"))
                .thenReturn(longPosition("HBL", 500, "170.0000"));
        when(positionService.markFor("HBL")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/positions/{symbol}", "HBL")
                        .with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netQuantity").value(500))
                .andExpect(jsonPath("$.unrealisedPnl").doesNotExist())
                .andExpect(jsonPath("$.markPrice").doesNotExist());
    }

    @Test
    @DisplayName("a never-traded symbol is a flat position, not a 404")
    void untradedSymbolIsFlat() throws Exception {
        when(positionService.positionFor(ACCOUNT, "NOSUCH"))
                .thenReturn(new PositionEntity(ACCOUNT, "NOSUCH"));
        when(positionService.markFor("NOSUCH")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/positions/{symbol}", "nosuch")
                        .with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netQuantity").value(0))
                .andExpect(jsonPath("$.symbol").value("NOSUCH"));
    }

    @Test
    @DisplayName("the P&L summary aggregates realised and unrealised and reports itself complete")
    void pnlSummaryComplete() throws Exception {
        PositionEntity hbl = longPosition("HBL", 500, "170.0000");
        PositionEntity ogdc = new PositionEntity(ACCOUNT, "OGDC");
        ogdc.applyFill(Side.BUY, new BigDecimal("200.0000"), 100);
        ogdc.applyFill(Side.SELL, new BigDecimal("210.0000"), 100);   // flat, realised 1,000

        when(positionService.positionsFor(eq(ACCOUNT), anyBoolean()))
                .thenReturn(List.of(hbl, ogdc));
        when(positionService.markFor("HBL")).thenReturn(Optional.of(new BigDecimal("174.0000")));
        when(positionService.markFor("OGDC")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/pnl").with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realisedPnl").value(1000.0))
                .andExpect(jsonPath("$.unrealisedPnl").value(2000.0))
                .andExpect(jsonPath("$.totalPnl").value(3000.0))
                .andExpect(jsonPath("$.openPositions").value(1))
                .andExpect(jsonPath("$.complete")
                        .value(true))
                .andExpect(jsonPath("$.grossExposure").value(87000.0));
    }

    @Test
    @DisplayName("an unmarked OPEN position makes the summary incomplete and is named")
    void pnlSummaryIncomplete() throws Exception {
        PositionEntity marked = longPosition("HBL", 500, "170.0000");
        PositionEntity unmarked = longPosition("LUCK", 20, "900.0000");

        when(positionService.positionsFor(eq(ACCOUNT), anyBoolean()))
                .thenReturn(List.of(marked, unmarked));
        when(positionService.markFor("HBL")).thenReturn(Optional.of(new BigDecimal("174.0000")));
        when(positionService.markFor("LUCK")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/pnl").with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete")
                        .value(false))
                .andExpect(jsonPath("$.unmarkedSymbols[0]").value("LUCK"))
                .andExpect(jsonPath("$.openPositions").value(2))
                // The unrealised total covers only HBL. Saying so is the point: a total that
                // silently omits a position is worse than no total.
                .andExpect(jsonPath("$.unrealisedPnl").value(2000.0));
    }

    @Test
    @DisplayName("the position list defaults to open positions only")
    void listDefaultsToOpenOnly() throws Exception {
        when(positionService.positionsFor(ACCOUNT, true))
                .thenReturn(List.of(longPosition("HBL", 500, "170.0000")));
        when(positionService.markFor(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/positions").with(trader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("HBL"));
    }

    @Test
    @DisplayName("no token is 401, in the platform error contract")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/api/v1/positions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a RISK officer can read positions - the role is read-only by design")
    void riskCanRead() throws Exception {
        when(positionService.positionsFor(anyString(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/positions").with(caller(ACCOUNT, OmsRoles.RISK)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an authenticated caller with no recognised role is 403")
    void unknownRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/positions").with(caller(ACCOUNT, "SOMETHING-ELSE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("the rows returned are scoped to the account in the CLAIM, not to a header")
    void rowsAreScopedToTheTokenAccount() throws Exception {
        when(positionService.positionsFor(anyString(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/positions")
                        .with(caller("ACC-REAL", OmsRoles.TRADER))
                        // Earlier phases would have trusted this header.
                        .header("X-Account-Id", "ACC-SOMEONE-ELSE"))
                .andExpect(status().isOk());

        // Role says "may call this endpoint"; the claim says "these rows". Getting only the first
        // half right is how one trader ends up able to read another one's book.
        org.mockito.Mockito.verify(positionService).positionsFor(eq("ACC-REAL"), anyBoolean());
    }
}
