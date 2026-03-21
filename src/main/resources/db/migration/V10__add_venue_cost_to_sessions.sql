ALTER TABLE sessions
    ADD COLUMN venue_cost NUMERIC(10, 2),
    ADD COLUMN cost_split TEXT;
