-- Baseline schema, generated from Hibernate's own DDL export (not
-- hand-typed) to guarantee it exactly matches what the entities already
-- produced -- see ARCHITECTURE.md ADR-019. Future schema changes are new
-- Vn__ files; ddl-auto is now `validate`, not `create-drop`.

create table audit_event (
    timestamp timestamp(6) with time zone not null,
    id uuid not null,
    notification_id uuid,
    detail varchar(500),
    event_type enum ('DELIVERY_ATTEMPTED','DELIVERY_EXHAUSTED','DELIVERY_FAILED','DELIVERY_QUEUED','DELIVERY_SUCCEEDED','IDEMPOTENCY_KEY_CONFLICT','NOTIFICATION_ACCEPTED','NOTIFICATION_DEDUPLICATED','NOTIFICATION_EXPIRED','NOTIFICATION_REJECTED','RETRY_SCHEDULED','ROUTING_DECIDED') not null,
    primary key (id)
);

create table delivery_attempt (
    attempt_count integer not null,
    max_attempts integer not null,
    created_at timestamp(6) with time zone not null,
    next_attempt_at timestamp(6) with time zone,
    sent_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null,
    version bigint not null,
    id uuid not null,
    notification_id uuid not null,
    last_failure_reason varchar(500),
    recipient_id varchar(255) not null,
    channel enum ('EMAIL','SMS','WEBHOOK') not null,
    last_failure_type enum ('AUTH_ERROR','INVALID_RECIPIENT','NONE','PERMANENT_PROVIDER_REJECTION','RATE_LIMITED','TIMEOUT','TRANSIENT_PROVIDER_ERROR'),
    status enum ('EXHAUSTED','FAILED','QUEUED','RETRY_SCHEDULED','SENDING','SKIPPED','SUCCEEDED') not null,
    primary key (id),
    constraint uk_delivery_dedup_boundary unique (notification_id, recipient_id, channel)
);

create table notification (
    created_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone,
    scheduled_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null,
    version bigint not null,
    id uuid not null,
    message varchar(2000) not null,
    event_id varchar(255) not null,
    idempotency_key varchar(255) not null,
    notification_type varchar(255) not null,
    request_fingerprint varchar(255) not null,
    source_system varchar(255) not null,
    subject varchar(255),
    priority enum ('HIGH','LOW','MEDIUM','URGENT') not null,
    severity enum ('CRITICAL','INFO','WARNING') not null,
    status enum ('DELIVERED','EXPIRED','FAILED','PARTIALLY_DELIVERED','PROCESSING','RECEIVED','REJECTED','ROUTED') not null,
    primary key (id),
    constraint uk_notification_dedup_boundary unique (source_system, idempotency_key)
);

create table notification_recipient (
    id uuid not null,
    notification_id uuid not null,
    recipient_id varchar(255) not null,
    primary key (id)
);

create table notification_requested_channel (
    position integer not null check ((position>=0)),
    notification_id uuid not null,
    channel enum ('EMAIL','SMS','WEBHOOK'),
    primary key (position, notification_id)
);

create table recipient_preference (
    opted_in boolean not null,
    id uuid not null,
    recipient_id varchar(255) not null,
    channel enum ('EMAIL','SMS','WEBHOOK') not null,
    primary key (id),
    constraint uk_recipient_channel unique (recipient_id, channel)
);

alter table if exists notification_recipient
   add constraint fk_notification_recipient_notification
   foreign key (notification_id)
   references notification;

alter table if exists notification_requested_channel
   add constraint fk_notification_requested_channel_notification
   foreign key (notification_id)
   references notification;
