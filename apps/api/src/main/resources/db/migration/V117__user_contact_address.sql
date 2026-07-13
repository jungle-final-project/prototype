ALTER TABLE users
  ADD COLUMN phone_number VARCHAR(30),
  ADD COLUMN postal_code VARCHAR(20),
  ADD COLUMN address_line1 VARCHAR(255),
  ADD COLUMN address_line2 VARCHAR(255);
