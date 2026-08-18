CREATE TABLE loyalty_member (
    id      VARCHAR(64) PRIMARY KEY,
    points  INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE redemption (
    id              BIGSERIAL PRIMARY KEY,
    member_id       VARCHAR(64)  NOT NULL,
    reward_name     VARCHAR(128) NOT NULL,
    cost            INT          NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    created_at      TIMESTAMP    NOT NULL
);

INSERT INTO loyalty_member (id, points) VALUES
    ('alice', 2450), ('bob', 180), ('carol', 620);
-- alice and bob are the users Keycloak Dev Services creates, so the OIDC
-- principal is used directly as the member ID. carol exists only for the
-- concurrency test, which bypasses HTTP entirely.