-- =====================================================================================
--  market-data-service baseline schema.
--
--  Owned exclusively by market-data-service. order-service reads instruments over REST
--  (and caches them in Redis); it never touches this schema.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS oms_marketdata;
SET search_path TO oms_marketdata;

-- -------------------------------------------------------------------------------------
--  instrument - the reference data every other service validates against.
--
--  Prices are NUMERIC, not the long ticks the engine uses internally (ADR 0002). This is
--  the durable, auditable representation; the tick conversion happens at the boundary.
-- -------------------------------------------------------------------------------------
CREATE TABLE instrument
(
    symbol             VARCHAR(16) PRIMARY KEY,
    name               VARCHAR(128)   NOT NULL,
    isin               VARCHAR(12)    NOT NULL,
    currency           CHAR(3)        NOT NULL,
    lot_size           BIGINT         NOT NULL CHECK (lot_size > 0),
    tick_size          NUMERIC(18, 4) NOT NULL CHECK (tick_size > 0),

    -- Fat-finger band, as a percentage either side of the reference price. A pre-trade
    -- control lives in reference data rather than in code because it is per instrument
    -- and changes without a deploy.
    price_band_percent NUMERIC(6, 2)  NOT NULL CHECK (price_band_percent > 0),

    -- Previous close, or the last official price. The anchor for the band check and the
    -- starting point for the tick simulator.
    reference_price    NUMERIC(18, 4) NOT NULL CHECK (reference_price > 0),

    status             VARCHAR(10)    NOT NULL
        CHECK (status IN ('ACTIVE', 'HALTED', 'DELISTED')),

    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_instrument_isin UNIQUE (isin)
);

-- The simulator and the instrument list endpoint both ask for "everything tradeable".
CREATE INDEX ix_instrument_active ON instrument (symbol) WHERE status = 'ACTIVE';
