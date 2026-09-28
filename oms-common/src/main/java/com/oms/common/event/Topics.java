package com.oms.common.event;

/**
 * Canonical topic names, plus the dead-letter convention.
 *
 * <p>Naming: {@code oms.<aggregate>.<event>.v<major>}. The major version is in the name,
 * not only in the payload, because a breaking change is then a new topic that old and new
 * consumers can straddle during a rollout - you never have to stop the world to change a
 * schema. Retention and partition counts are declared in ops/kafka-topics.md and created
 * by the topic bootstrap in docker-compose.
 */
public final class Topics {

    /** order-service -> matching-engine. Key: symbol. */
    public static final String ORDERS_ACCEPTED = "oms.orders.accepted.v1";

    /** order-service -> matching-engine. Key: symbol. */
    public static final String ORDERS_CANCEL_REQUESTS = "oms.orders.cancel-requests.v1";

    /** matching-engine -> order-service. Cancel confirmations. Key: symbol. */
    public static final String EXECUTION_REPORTS = "oms.orders.execution-reports.v1";

    /** matching-engine -> order-service, position-service. Key: symbol. */
    public static final String TRADES_EXECUTED = "oms.trades.executed.v1";

    /** order-service -> audit/analytics. Key: order id. */
    public static final String ORDERS_LIFECYCLE = "oms.orders.lifecycle.v1";

    /** market-data-service -> subscribers. Key: symbol. High volume, short retention. */
    public static final String MARKET_DATA_TICKS = "oms.marketdata.ticks.v1";

    /** Suffix appended by Spring Kafka error handling for the dead-letter topic. */
    public static final String DLT_SUFFIX = ".dlt";

    private Topics() {
    }

    public static String deadLetterFor(String topic) {
        return topic + DLT_SUFFIX;
    }
}
