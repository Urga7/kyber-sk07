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

## 6. Expose the primary to app-02 (HA — S3.7)

The second API instance (`kyber-app-02`, see `04-app-02-and-ha.md`) connects to **this**
primary over the DMZ — a **shared single primary**, no replica. Have PostgreSQL listen on the
DMZ address and authorize only app-02's `kyber_api` connections, over TLS:

```
# /etc/postgresql/16/main/postgresql.conf
listen_addresses = 'localhost,192.168.7.10,2001:1470:fffd:99::10'

# /etc/postgresql/16/main/pg_hba.conf  (append; app-02 only)
hostssl  kyber  kyber_api  192.168.7.11/32              scram-sha-256
hostssl  kyber  kyber_api  2001:1470:fffd:99::11/128    scram-sha-256
```

```
sudo systemctl restart postgresql
```

app-01's own API keeps connecting over `127.0.0.1` (unchanged); only app-02 uses
`192.168.7.10`. `hostssl` forces TLS — PostgreSQL 16 on Ubuntu enables `ssl = on` with a
snakeoil cert by default, which is adequate for this intra-DMZ link (encrypted, and the source
is pinned to app-02's address). app-02→app-01:5432 is intra-DMZ (L2), so no `kyber-rtr`
firewall rule is needed.

> Single primary = **accepted SPOF** (losing app-01 loses the DB). The deferred hardening
> options — streaming replication + manual promote, or automatic failover via Patroni on the
> S4 etcd cluster — are noted in `04-app-02-and-ha.md`.
