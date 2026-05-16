CREATE TABLE orders (
    id           UUID            PRIMARY KEY,
    customer_id  VARCHAR(128)    NOT NULL,
    amount       NUMERIC(19, 4)  NOT NULL
);
