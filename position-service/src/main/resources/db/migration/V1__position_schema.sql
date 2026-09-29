-- =====================================================================================
--  position-service baseline schema.
--
--  Every row here is DERIVED from oms.trades.executed.v1. The topic is the source of
--  truth; these tables are a materialised read model that can be rebuilt by replaying it
--  from the beginning, which is why trades are retained for 30 days.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS oms_position;
SET search_path TO oms_position;

-- -------------------------------------------------------------------------------------
--  position - current holding per (account, symbol).
--
--  Representation note. The obvious columns are net_quantity and avg_cost, but storing a
--  rounded average and then recomputing from it compounds rounding error on every fill.
--  Instead we store open_cost - the EXACT total cost of the currently open quantity - and
--  derive the average on read. Average-cost accounting needs one division per reduction
--  and no more, and a full close removes exactly the cost that went in, so the books
--  balance to the last paisa when a position is flattened.
-- -------------------------------------------------------------------------------------
CREATE TABLE position
(
    account_id    VARCHAR(32)    NOT NULL,
    symbol        VARCHAR(16)    NOT NULL,

    -- Signed: positive is long, negative is short, zero is flat.
    net_quantity  BIGINT         NOT NULL DEFAULT 0,

    -- Total cost of the OPEN quantity, always >= 0. For a short position this is the
    -- proceeds received, which is the cost of buying it back at the average sale price.
    open_cost     NUMERIC(24, 8) NOT NULL DEFAULT 0 CHECK (open_cost >= 0),

    -- Locked in when a position is reduced. Can be negative; that is a loss, not an error.
    realised_pnl  NUMERIC(24, 4) NOT NULL DEFAULT 0,

    bought_quantity BIGINT       NOT NULL DEFAULT 0 CHECK (bought_quantity >= 0),
    sold_quantity   BIGINT       NOT NULL DEFAULT 0 CHECK (sold_quantity >= 0),
    fill_count      INTEGER      NOT NULL DEFAULT 0 CHECK (fill_count >= 0),

    revision      INTEGER        NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (account_id, symbol),

    -- A flat position must have no open cost. Without this, a rounding bug that leaves
    -- cost behind after a full close would be invisible until somebody reconciled by hand.
    CONSTRAINT ck_position_flat_has_no_cost
        CHECK (net_quantity <> 0 OR open_cost = 0)
);

CREATE INDEX ix_position_account ON position (account_id);

-- Only non-flat positions matter for a risk report, and after a few days of trading most
-- rows are flat.
CREATE INDEX ix_position_open ON position (account_id, symbol) WHERE net_quantity <> 0;

-- -------------------------------------------------------------------------------------
--  trade_ledger - one immutable row per (trade, account).
--
--  Two jobs, and both are load-bearing:
--
--  1. IDEMPOTENCY. The primary key (trade_id, account_id) is what makes at-least-once
--     delivery safe. A replayed TradeExecutedEvent conflicts here and the handler treats
--     the conflict as "already applied". A natural key beats a separate bookkeeping table
--     because the row that records the effect IS the record that it happened.
--
--  2. AUDIT. position is a running total; this is the workings. Storing the position and
--     average cost AFTER each fill means a disputed P&L figure can be traced fill by fill
--     rather than recomputed and hoped over.
-- -------------------------------------------------------------------------------------
CREATE TABLE trade_ledger
(
    trade_id         UUID           NOT NULL,
    account_id       VARCHAR(32)    NOT NULL,
    symbol           VARCHAR(16)    NOT NULL,
    order_id         UUID           NOT NULL,
    side             VARCHAR(4)     NOT NULL CHECK (side IN ('BUY', 'SELL')),
    price            NUMERIC(18, 4) NOT NULL CHECK (price > 0),
    quantity         BIGINT         NOT NULL CHECK (quantity > 0),

    -- What this single fill realised. Zero when the fill opened or increased a position.
    realised_delta   NUMERIC(24, 4) NOT NULL,

    -- Position state after this fill, so the ledger reconstructs itself.
    net_after        BIGINT         NOT NULL,
    avg_cost_after   NUMERIC(24, 8) NOT NULL,

    engine_seq       BIGINT         NOT NULL,
    executed_at      TIMESTAMPTZ    NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (trade_id, account_id)
);

CREATE INDEX ix_trade_ledger_account_time ON trade_ledger (account_id, executed_at DESC);
CREATE INDEX ix_trade_ledger_symbol ON trade_ledger (symbol, executed_at DESC);

-- Append-only, enforced by the database. Same reasoning as order_audit: an UPDATE against
-- a financial ledger is always either a bug or an attack.
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'oms_position.trade_ledger is append-only (attempted %)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trade_ledger_immutable
    BEFORE UPDATE OR DELETE
    ON trade_ledger
    FOR EACH ROW
EXECUTE FUNCTION reject_ledger_mutation();
