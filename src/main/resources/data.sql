-- Demo recipient preference data. Only opt-outs need a row (see
-- RecipientPreference's Javadoc) -- absence of a row means opted-in.
INSERT INTO recipient_preference (id, recipient_id, channel, opted_in) VALUES
    ('11111111-1111-1111-1111-111111111111', 'alice@example.com', 'SMS', false),
    ('22222222-2222-2222-2222-222222222222', 'bob@example.com', 'EMAIL', false);
