-- The address is joined in SQL, so only the database's error can carry it whole.
CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(100) NOT NULL CONSTRAINT customers_email_key UNIQUE);
INSERT INTO customers VALUES (1, 'ada.lovelace' || '@example.com');
