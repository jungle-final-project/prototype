ALTER TABLE technicians
    ADD COLUMN user_id BIGINT REFERENCES users(id),
    ADD COLUMN provider_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN verification_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN business_name VARCHAR(160),
    ADD COLUMN contact_phone VARCHAR(40),
    ADD COLUMN rejection_reason VARCHAR(1000),
    ADD COLUMN approved_by_admin_id BIGINT REFERENCES users(id),
    ADD COLUMN approved_at TIMESTAMPTZ;

ALTER TABLE technicians
    ADD CONSTRAINT chk_technicians_provider_type
        CHECK (provider_type IN ('INTERNAL', 'EXTERNAL')),
    ADD CONSTRAINT chk_technicians_verification_status
        CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT chk_external_technician_has_user
        CHECK (provider_type = 'INTERNAL' OR user_id IS NOT NULL);

CREATE UNIQUE INDEX ux_technicians_user
    ON technicians(user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX ix_technicians_external_review
    ON technicians(provider_type, verification_status, status, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE assembly_requests
    ADD COLUMN contact_name VARCHAR(100),
    ADD COLUMN contact_phone VARCHAR(40),
    ADD COLUMN postal_code VARCHAR(20),
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255);

ALTER TABLE assembly_offers
    ADD COLUMN submitted_by_user_id BIGINT REFERENCES users(id),
    ADD COLUMN submitted_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE assembly_offer_activities (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    assembly_offer_id BIGINT NOT NULL REFERENCES assembly_offers(id) ON DELETE CASCADE,
    actor_user_id BIGINT REFERENCES users(id),
    action VARCHAR(30) NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_assembly_offer_activity_action CHECK (
        action IN ('SUBMITTED', 'UPDATED', 'WITHDRAWN', 'ADMIN_UPDATED', 'ADMIN_WITHDRAWN')
    )
);

CREATE INDEX ix_assembly_offer_activities_offer
    ON assembly_offer_activities(assembly_offer_id, created_at, id);

INSERT INTO users (
    public_id, email, password_hash, name, role,
    terms_accepted_at, marketing_accepted_at, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000001006',
    'technician@example.com',
    '$2a$10$MD8WYrm/3tXHCRNCCtUiH.TuIoQzGBaDZmMlpWCtT0eTsnxLT8Tly',
    'Demo External Technician',
    'USER', now(), NULL, now(), now()
) ON CONFLICT (email) DO NOTHING;

INSERT INTO technicians (
    user_id, display_name, initials, status, provider_type, verification_status,
    business_name, contact_phone, service_regions, service_types, specialties,
    rating, completed_jobs, avg_response_minutes, assembly_fee, delivery_fee,
    lead_time_days, parts_price_adjustment, sort_priority, standard_as_accepted,
    seeded, approved_at, created_at, updated_at
)
SELECT
    u.id, '최민석 기사', '최', 'ACTIVE', 'EXTERNAL', 'APPROVED',
    '민석 PC 조립', '010-0000-1004',
    '["서울","경기","인천"]'::jsonb,
    '["FULL_SERVICE","ASSEMBLY_ONLY"]'::jsonb,
    '["고성능 게이밍 PC","저소음 조립"]'::jsonb,
    4.7, 41, 15, 70000, 12000, 2, 0, 100, true,
    true, now(), now(), now()
FROM users u
WHERE u.email = 'technician@example.com'
  AND NOT EXISTS (SELECT 1 FROM technicians t WHERE t.user_id = u.id);
