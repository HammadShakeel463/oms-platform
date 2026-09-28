-- Demo accounts with risk limits, so a fresh stack is usable immediately.
--
-- This is reference data, not test fixtures: it ships in the main migration path because
-- an empty account table makes every order fail, and a "run this script after startup"
-- step is a step somebody forgets. Real limits would be administered through an API.

SET search_path TO oms_order;

INSERT INTO account (account_id, display_name, max_order_notional, max_position_qty)
VALUES ('ACC-TRADER-1', 'Demo Trader One', 5000000.0000, 100000),
       ('ACC-TRADER-2', 'Demo Trader Two', 5000000.0000, 100000),
       ('ACC-MM-1', 'Demo Market Maker', 50000000.0000, 1000000),
       ('ACC-SMALL-1', 'Demo Retail Account', 250000.0000, 5000)
ON CONFLICT (account_id) DO NOTHING;
