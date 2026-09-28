package com.oms.order.reference;

import com.oms.common.error.NotFoundException;
import com.oms.common.money.Ticks;
import com.oms.common.reference.InstrumentStatus;
import com.oms.common.reference.InstrumentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * In-memory catalogue used when {@code oms.reference.source=static}.
 *
 * <p>Exists so order-service runs, and its integration tests pass, without
 * market-data-service being up - which matters while the platform is built in phases, and
 * matters again every time someone wants to work on order-service alone.
 *
 * <p>Selected by {@code @ConditionalOnProperty}, so exactly one {@link InstrumentClient}
 * bean exists in any given context. This is the Spring way to express a compile-time-ish
 * choice at configuration time: not an {@code if} inside a single implementation, and not
 * two beans plus {@code @Primary}, which merely hides the ambiguity.
 */
@Component
@ConditionalOnProperty(name = "oms.reference.source", havingValue = "static")
public class StaticInstrumentClient implements InstrumentClient {

    private static final Logger log = LoggerFactory.getLogger(StaticInstrumentClient.class);

    private final Map<String, InstrumentView> catalogue;

    public StaticInstrumentClient() {
        this.catalogue = List.of(
                instrument("HBL", "Habib Bank Limited", "PK0001901014", "172.4500"),
                instrument("OGDC", "Oil & Gas Development Company", "PK0069201012", "205.9000"),
                instrument("LUCK", "Lucky Cement", "PK0034601013", "915.7500"),
                instrument("ENGRO", "Engro Corporation", "PK0005401015", "318.2000"),
                instrument("PSO", "Pakistan State Oil", "PK0002101014", "182.6000"),
                instrument("MCB", "MCB Bank Limited", "PK0005601010", "264.3000"),
                instrument("TRG", "TRG Pakistan", "PK0081201015", "58.7500")
        ).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                InstrumentView::symbol, Function.identity()));

        log.warn("Using the STATIC instrument catalogue ({} symbols). "
                + "Set oms.reference.source=remote to use market-data-service.", catalogue.size());
    }

    private static InstrumentView instrument(String symbol, String name, String isin,
                                             String referencePrice) {
        return new InstrumentView(
                symbol,
                name,
                isin,
                "PKR",
                1L,                                     // lot size
                Ticks.fromDecimal(new BigDecimal("0.0100")),  // 1 paisa tick
                new BigDecimal("10.00"),                // +/- 10% fat-finger band
                Ticks.fromDecimal(new BigDecimal(referencePrice)),
                InstrumentStatus.ACTIVE);
    }

    @Override
    public InstrumentView findBySymbol(String symbol) {
        InstrumentView view = catalogue.get(symbol);
        if (view == null) {
            throw NotFoundException.instrument(symbol);
        }
        return view;
    }
}
