# PostgreSQL — database for the REST API (S3.1, S3.4)

Stands up the **PostgreSQL primary** on `kyber-app-01` with the two-related-resources
schema (`customers` 1—N `orders`) that the REST API serves. Streaming replication to
`sk07-app-02` (S3.1 optional HA) is deferred to that VM's runbook.

## 1. Install

Ubuntu's repo PostgreSQL is fine (16 on 24.04 LTS).

```
sudo apt -y install postgresql
sudo systemctl enable --now postgresql
sudo -u postgres psql -c 'SELECT version();'
```

The API talks to PostgreSQL over the local loopback only, so the default
`listen_addresses = 'localhost'` is correct — **do not** open it to the DMZ. (When
app-02 replication is set up later, you'll add the replica's address explicitly.)

## 2. Role and database

Pick a strong password and keep it out of the repo (it goes only in the API's
systemd `EnvironmentFile`, see `03-rest-api.md` §3). Placeholder below: `CHANGE_ME`.

```
sudo -u postgres psql <<'SQL'
CREATE ROLE kyber_api WITH LOGIN PASSWORD 'CHANGE_ME';
CREATE DATABASE kyber OWNER kyber_api;
SQL
```

## 3. Schema

Two related resources with a foreign-key relationship (`orders.customer_id` →
`customers.id`). `ON DELETE CASCADE` keeps the API's delete semantics simple.

Create `/tmp/kyber-schema.sql`:

```sql
CREATE TABLE customers (
    id         SERIAL PRIMARY KEY,
    name       TEXT        NOT NULL,
    email      TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER     NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    product     TEXT        NOT NULL,
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    amount      NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
```

Apply it as the `kyber_api` owner so it owns the tables:

```
sudo -u postgres psql -d kyber -f /tmp/kyber-schema.sql
sudo -u postgres psql -d kyber -c 'ALTER TABLE customers OWNER TO kyber_api; ALTER TABLE orders OWNER TO kyber_api;'
rm /tmp/kyber-schema.sql
```

## 4. Seed data (optional, for the acceptance tests)

```
sudo -u postgres psql -d kyber <<'SQL'
INSERT INTO customers (name, email) VALUES
  ('Alice Kyber', 'alice@kyber.local'),
  ('Bob Kyber',   'bob@kyber.local');
INSERT INTO orders (customer_id, product, quantity, amount) VALUES
  (1, 'Widget', 3, 29.97),
  (1, 'Gadget', 1, 14.99),
  (2, 'Sprocket', 10, 99.90);
SQL
```

## 5. Verify

```
PGPASSWORD=CHANGE_ME psql -h 127.0.0.1 -U kyber_api -d kyber -c '\dt'
PGPASSWORD=CHANGE_ME psql -h 127.0.0.1 -U kyber_api -d kyber \
  -c 'SELECT c.name, count(o.id) FROM customers c LEFT JOIN orders o ON o.customer_id=c.id GROUP BY c.name;'
```

Both should succeed over `127.0.0.1` with the `kyber_api` credentials — that is exactly
the connection string the API will use in `03-rest-api.md`.
