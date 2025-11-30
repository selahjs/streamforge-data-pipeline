CREATE TABLE items (
                       id SERIAL PRIMARY KEY,
                       external_id VARCHAR(255) NOT NULL UNIQUE,
                       name VARCHAR(255) NOT NULL,
                       quantity INTEGER,
                       expiry_date DATE
);

-- Add index for external_id (even though it's unique, Flyway keeps code readable)
CREATE INDEX idx_items_external_id ON items (external_id);
