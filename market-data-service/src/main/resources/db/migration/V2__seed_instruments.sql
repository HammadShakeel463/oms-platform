-- Pakistan Stock Exchange symbols, so the platform is usable the moment it starts.
--
-- Reference data ships in the main migration path rather than as a "run this afterwards"
-- step, because an empty instrument table makes every order fail validation and a manual
-- step is a step somebody forgets. Reference prices are representative, not live.

SET search_path TO oms_marketdata;

INSERT INTO instrument (symbol, name, isin, currency, lot_size, tick_size,
                        price_band_percent, reference_price, status)
VALUES ('HBL',   'Habib Bank Limited',              'PK0001901014', 'PKR', 1, 0.0100, 10.00, 172.4500, 'ACTIVE'),
       ('OGDC',  'Oil & Gas Development Company',   'PK0069201012', 'PKR', 1, 0.0100, 10.00, 205.9000, 'ACTIVE'),
       ('LUCK',  'Lucky Cement',                    'PK0034601013', 'PKR', 1, 0.0100,  7.50, 915.7500, 'ACTIVE'),
       ('ENGRO', 'Engro Corporation',               'PK0005401015', 'PKR', 1, 0.0100, 10.00, 318.2000, 'ACTIVE'),
       ('PSO',   'Pakistan State Oil',              'PK0002101014', 'PKR', 1, 0.0100, 10.00, 182.6000, 'ACTIVE'),
       ('MCB',   'MCB Bank Limited',                'PK0005601010', 'PKR', 1, 0.0100, 10.00, 264.3000, 'ACTIVE'),
       ('TRG',   'TRG Pakistan',                    'PK0081201015', 'PKR', 1, 0.0100, 15.00,  58.7500, 'ACTIVE'),
       ('SYS',   'Systems Limited',                 'PK0091201011', 'PKR', 1, 0.0100, 12.50, 412.9000, 'ACTIVE'),
       -- One halted and one delisted instrument, so the rejection paths in pre-trade risk
       -- are exercisable against real reference data rather than only in unit tests.
       ('PIAA',  'Pakistan International Airlines', 'PK0002501013', 'PKR', 1, 0.0100, 10.00,  12.4000, 'HALTED'),
       ('DEAD',  'Delisted Test Instrument',        'PK0000000019', 'PKR', 1, 0.0100, 10.00,  50.0000, 'DELISTED')
ON CONFLICT (symbol) DO NOTHING;
