CREATE TABLE technicians (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    display_name VARCHAR(120) NOT NULL,
    initials VARCHAR(12) NOT NULL,
    profile_image_url TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'INACTIVE',
    service_regions JSONB NOT NULL DEFAULT '[]'::jsonb,
    service_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    specialties JSONB NOT NULL DEFAULT '[]'::jsonb,
    rating NUMERIC(2, 1) NOT NULL DEFAULT 0,
    completed_jobs INTEGER NOT NULL DEFAULT 0,
    avg_response_minutes INTEGER NOT NULL DEFAULT 0,
    assembly_fee BIGINT NOT NULL DEFAULT 0,
    delivery_fee BIGINT NOT NULL DEFAULT 0,
    lead_time_days INTEGER NOT NULL DEFAULT 1,
    parts_price_adjustment BIGINT NOT NULL DEFAULT 0,
    sort_priority INTEGER NOT NULL DEFAULT 100,
    standard_as_accepted BOOLEAN NOT NULL DEFAULT false,
    seeded BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_technicians_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_technicians_rating CHECK (rating >= 0 AND rating <= 5),
    CONSTRAINT chk_technicians_nonnegative CHECK (
        completed_jobs >= 0 AND avg_response_minutes >= 0 AND assembly_fee >= 0
        AND delivery_fee >= 0 AND lead_time_days >= 1
    )
);

CREATE INDEX ix_technicians_operational
    ON technicians(status, sort_priority, rating DESC, avg_response_minutes)
    WHERE deleted_at IS NULL;

CREATE TABLE assembly_requests (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    request_no VARCHAR(40) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    quote_draft_id BIGINT REFERENCES quote_drafts(id),
    idempotency_key VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    service_type VARCHAR(24) NOT NULL,
    region VARCHAR(60) NOT NULL,
    preferred_date DATE NOT NULL,
    delivery_method VARCHAR(24) NOT NULL,
    note VARCHAR(1000),
    as_policy_accepted BOOLEAN NOT NULL,
    estimated_parts_price BIGINT NOT NULL,
    item_count INTEGER NOT NULL,
    quote_signature VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    compatibility_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    selected_offer_id BIGINT,
    cancellation_reason VARCHAR(1000),
    matched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_assembly_requests_user_idempotency UNIQUE(user_id, idempotency_key),
    CONSTRAINT chk_assembly_requests_status CHECK (status IN (
        'REQUESTED', 'OFFERED', 'MATCHED', 'CONFIRMED', 'ASSEMBLING', 'SHIPPED', 'COMPLETED', 'CANCELLED'
    )),
    CONSTRAINT chk_assembly_requests_service_type CHECK (service_type IN ('FULL_SERVICE', 'ASSEMBLY_ONLY')),
    CONSTRAINT chk_assembly_requests_delivery CHECK (delivery_method IN ('DELIVERY', 'PICKUP')),
    CONSTRAINT chk_assembly_requests_values CHECK (estimated_parts_price >= 0 AND item_count > 0)
);

CREATE INDEX ix_assembly_requests_user_created
    ON assembly_requests(user_id, created_at DESC);
CREATE INDEX ix_assembly_requests_admin_status
    ON assembly_requests(status, created_at DESC);

CREATE TABLE assembly_request_items (
    id BIGSERIAL PRIMARY KEY,
    assembly_request_id BIGINT NOT NULL REFERENCES assembly_requests(id) ON DELETE CASCADE,
    part_id BIGINT REFERENCES parts(id),
    part_public_id UUID NOT NULL,
    category VARCHAR(40) NOT NULL,
    name VARCHAR(255) NOT NULL,
    manufacturer VARCHAR(120),
    quantity INTEGER NOT NULL,
    unit_price_snapshot BIGINT NOT NULL,
    line_total BIGINT NOT NULL,
    external_offer_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_assembly_request_items_values CHECK (
        quantity > 0 AND unit_price_snapshot >= 0 AND line_total >= 0
    )
);

CREATE INDEX ix_assembly_request_items_request
    ON assembly_request_items(assembly_request_id, id);

CREATE TABLE assembly_offers (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    assembly_request_id BIGINT NOT NULL REFERENCES assembly_requests(id) ON DELETE CASCADE,
    technician_id BIGINT NOT NULL REFERENCES technicians(id),
    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    technician_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    confirmed_parts_price BIGINT NOT NULL,
    assembly_fee BIGINT NOT NULL,
    delivery_fee BIGINT NOT NULL,
    final_price BIGINT NOT NULL,
    lead_time_days INTEGER NOT NULL,
    stock_status VARCHAR(255) NOT NULL,
    admin_note VARCHAR(1000),
    selected_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_assembly_offers_request_technician UNIQUE(assembly_request_id, technician_id),
    CONSTRAINT chk_assembly_offers_status CHECK (status IN ('AVAILABLE', 'SELECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT chk_assembly_offers_values CHECK (
        confirmed_parts_price >= 0 AND assembly_fee >= 0 AND delivery_fee >= 0
        AND final_price = confirmed_parts_price + assembly_fee + delivery_fee
        AND lead_time_days >= 1
    )
);

CREATE UNIQUE INDEX ux_assembly_offers_one_selected
    ON assembly_offers(assembly_request_id)
    WHERE status = 'SELECTED';
CREATE INDEX ix_assembly_offers_request_status
    ON assembly_offers(assembly_request_id, status, created_at);

ALTER TABLE assembly_requests
    ADD CONSTRAINT fk_assembly_requests_selected_offer
    FOREIGN KEY (selected_offer_id) REFERENCES assembly_offers(id);

CREATE TABLE assembly_payments (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    assembly_request_id BIGINT NOT NULL UNIQUE REFERENCES assembly_requests(id) ON DELETE CASCADE,
    amount BIGINT NOT NULL,
    method VARCHAR(24) NOT NULL DEFAULT 'VIRTUAL',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_assembly_payments_method CHECK (method = 'VIRTUAL'),
    CONSTRAINT chk_assembly_payments_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT chk_assembly_payments_amount CHECK (amount >= 0)
);

CREATE TABLE assembly_request_status_history (
    id BIGSERIAL PRIMARY KEY,
    assembly_request_id BIGINT NOT NULL REFERENCES assembly_requests(id) ON DELETE CASCADE,
    actor_user_id BIGINT REFERENCES users(id),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_assembly_request_status_history_request
    ON assembly_request_status_history(assembly_request_id, created_at, id);

INSERT INTO technicians (
    display_name, initials, status, service_regions, service_types, specialties,
    rating, completed_jobs, avg_response_minutes, assembly_fee, delivery_fee,
    lead_time_days, parts_price_adjustment, sort_priority, standard_as_accepted, seeded
) VALUES
    ('박준호 기사', '박', 'ACTIVE', '["서울","경기","인천","대전","대구","부산","광주"]'::jsonb,
     '["FULL_SERVICE","ASSEMBLY_ONLY"]'::jsonb, '["고성능 게이밍 PC","균형 구성"]'::jsonb,
     4.9, 184, 12, 65000, 0, 2, 5000, 10, true, true),
    ('김도윤 기사', '김', 'ACTIVE', '["서울","경기","인천","대전","대구","부산","광주"]'::jsonb,
     '["FULL_SERVICE","ASSEMBLY_ONLY"]'::jsonb, '["당일 조립","빠른 배송"]'::jsonb,
     4.8, 132, 8, 80000, 15000, 1, 15000, 20, true, true),
    ('이현우 기사', '이', 'ACTIVE', '["서울","경기","인천","대전","대구","부산","광주"]'::jsonb,
     '["FULL_SERVICE","ASSEMBLY_ONLY"]'::jsonb, '["저소음 세팅","선정리"]'::jsonb,
     5.0, 96, 18, 95000, 20000, 3, -12000, 30, true, true);
