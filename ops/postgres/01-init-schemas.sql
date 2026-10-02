-- =====================================================================================
--  One database, one ROLE PER SERVICE, one schema per service.
--
--  Each service connects as its own least-privileged role and can only touch its own
--  schema. That is what makes "schema per service, no shared tables" (ADR 0001) an
--  enforced boundary rather than a convention: order-service physically cannot read
--  oms_position, so a well-meaning join across services fails at the database instead of
--  quietly creating a distributed monolith.
--
--  Flyway creates the schemas and the tables; this script only creates the roles and the
--  privileges that let it. Runs once, from the Postgres image entrypoint.
-- =====================================================================================

-- Passwords here are for local development only. In Kubernetes these come from a Secret,
-- and in a real deployment from a managed secret store with rotation.
CREATE ROLE oms_order      LOGIN PASSWORD 'oms_order';
CREATE ROLE oms_marketdata LOGIN PASSWORD 'oms_marketdata';
CREATE ROLE oms_position   LOGIN PASSWORD 'oms_position';

-- Each role owns its schema, so Flyway can create tables, indexes and triggers in it.
CREATE SCHEMA IF NOT EXISTS oms_order      AUTHORIZATION oms_order;
CREATE SCHEMA IF NOT EXISTS oms_marketdata AUTHORIZATION oms_marketdata;
CREATE SCHEMA IF NOT EXISTS oms_position   AUTHORIZATION oms_position;

-- No cross-schema access is granted. Omission is the point: the absence of a GRANT is
-- what enforces the service boundary.

-- Revoke the default ability of every role to create objects in the public schema. Without
-- this, any service could create a table in public and two services could start sharing it.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Each role needs its own schema first on the search path, so unqualified DDL from Flyway
-- lands where it should even if a migration forgets to SET search_path.
ALTER ROLE oms_order      SET search_path TO oms_order;
ALTER ROLE oms_marketdata SET search_path TO oms_marketdata;
ALTER ROLE oms_position   SET search_path TO oms_position;

-- matching-engine and api-gateway appear nowhere in this file, deliberately: the engine
-- holds its books in memory and the gateway holds no state at all. Neither has a database
-- role, so neither can acquire a dependency on one by accident.
