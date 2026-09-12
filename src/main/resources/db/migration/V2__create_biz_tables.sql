-- =============================================================================
-- V2 ERP 业务表结构（PostgreSQL）
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，否则 checksum 校验会失败。
-- 后续变更请新增 V4__xxx.sql、V5__xxx.sql。
-- 约定：
--   1. 所有业务表均带 ent_code 租户列，由 MyBatis-Plus 租户插件自动追加条件
--   2. 金额统一 numeric(18,2)，数量统一 numeric(18,3)
--   3. 保留 IF NOT EXISTS：使本脚本对「已用旧手工脚本建过表」的存量库也安全
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 主数据
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    product_code    VARCHAR(64)  NOT NULL,
    product_name    VARCHAR(128) NOT NULL,
    spec            VARCHAR(128),
    unit            VARCHAR(16)  NOT NULL DEFAULT '个',
    category        VARCHAR(64),
    safety_stock    NUMERIC(18,3) NOT NULL DEFAULT 0,
    unit_price      NUMERIC(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_code ON product (ent_code, product_code);
CREATE INDEX IF NOT EXISTS idx_product_ent ON product (ent_code);

CREATE TABLE IF NOT EXISTS customer (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    customer_code   VARCHAR(64)  NOT NULL,
    customer_name   VARCHAR(128) NOT NULL,
    contact_person  VARCHAR(64),
    contact_phone   VARCHAR(32),
    region          VARCHAR(64),
    credit_limit    NUMERIC(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_code ON customer (ent_code, customer_code);
CREATE INDEX IF NOT EXISTS idx_customer_ent ON customer (ent_code);

CREATE TABLE IF NOT EXISTS supplier (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    supplier_code   VARCHAR(64)  NOT NULL,
    supplier_name   VARCHAR(128) NOT NULL,
    contact_person  VARCHAR(64),
    contact_phone   VARCHAR(32),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_code ON supplier (ent_code, supplier_code);
CREATE INDEX IF NOT EXISTS idx_supplier_ent ON supplier (ent_code);

-- ---------------------------------------------------------------------------
-- 销售模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sales_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    customer_name   VARCHAR(128),
    order_date      DATE         NOT NULL,
    delivery_date   DATE,
    total_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft',
    remark          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_no ON sales_order (ent_code, order_no);
CREATE INDEX IF NOT EXISTS idx_sales_order_ent_date ON sales_order (ent_code, order_date);

CREATE TABLE IF NOT EXISTS sales_order_item (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    unit_price      NUMERIC(18,2) NOT NULL DEFAULT 0,
    amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sales_item_ent_order ON sales_order_item (ent_code, order_no);

CREATE TABLE IF NOT EXISTS shipment (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    shipment_no     VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    shipment_date   DATE         NOT NULL,
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    logistics_no    VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipment_no ON shipment (ent_code, shipment_no);
CREATE INDEX IF NOT EXISTS idx_shipment_ent_date ON shipment (ent_code, shipment_date);

CREATE TABLE IF NOT EXISTS accounts_receivable (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    customer_name   VARCHAR(128),
    order_no        VARCHAR(64),
    due_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    received_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    due_date        DATE,
    aging_days      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'unpaid',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ar_ent_customer ON accounts_receivable (ent_code, customer_id);

-- ---------------------------------------------------------------------------
-- 采购模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS purchase_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    supplier_id     BIGINT       NOT NULL,
    supplier_name   VARCHAR(128),
    order_date      DATE         NOT NULL,
    total_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_purchase_order_no ON purchase_order (ent_code, order_no);
CREATE INDEX IF NOT EXISTS idx_purchase_order_ent_date ON purchase_order (ent_code, order_date);

CREATE TABLE IF NOT EXISTS purchase_order_item (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    received_qty    NUMERIC(18,3) NOT NULL DEFAULT 0,
    unit_price      NUMERIC(18,2) NOT NULL DEFAULT 0,
    amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_purchase_item_ent_order ON purchase_order_item (ent_code, order_no);

CREATE TABLE IF NOT EXISTS purchase_receive (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    receive_no      VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT,
    product_name    VARCHAR(128),
    receive_date    DATE         NOT NULL,
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_purchase_receive_no ON purchase_receive (ent_code, receive_no);
CREATE INDEX IF NOT EXISTS idx_purchase_receive_ent_date ON purchase_receive (ent_code, receive_date);

CREATE TABLE IF NOT EXISTS accounts_payable (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    supplier_id     BIGINT       NOT NULL,
    supplier_name   VARCHAR(128),
    order_no        VARCHAR(64),
    due_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(18,2) NOT NULL DEFAULT 0,
    due_date        DATE,
    aging_days      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'unpaid',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ap_ent_supplier ON accounts_payable (ent_code, supplier_id);

-- ---------------------------------------------------------------------------
-- 仓储模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    warehouse       VARCHAR(64)  NOT NULL DEFAULT '主仓',
    location        VARCHAR(64),
    batch_no        VARCHAR(64),
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    safety_stock    NUMERIC(18,3) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inventory_ent_product ON inventory (ent_code, product_id);
CREATE INDEX IF NOT EXISTS idx_inventory_ent_warehouse ON inventory (ent_code, warehouse);

CREATE TABLE IF NOT EXISTS stock_movement (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    movement_no     VARCHAR(64)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    warehouse       VARCHAR(64),
    movement_type   VARCHAR(16)  NOT NULL,
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    movement_date   DATE         NOT NULL,
    related_no      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_stock_movement_ent_date ON stock_movement (ent_code, movement_date);

-- ---------------------------------------------------------------------------
-- 生产模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    planned_qty     NUMERIC(18,3) NOT NULL DEFAULT 0,
    completed_qty   NUMERIC(18,3) NOT NULL DEFAULT 0,
    scrap_qty       NUMERIC(18,3) NOT NULL DEFAULT 0,
    plan_start_date DATE,
    plan_end_date   DATE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'planned',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_work_order_no ON work_order (ent_code, order_no);
CREATE INDEX IF NOT EXISTS idx_work_order_ent_start ON work_order (ent_code, plan_start_date);

CREATE TABLE IF NOT EXISTS work_order_material (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    required_qty    NUMERIC(18,3) NOT NULL DEFAULT 0,
    issued_qty      NUMERIC(18,3) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_work_order_material_ent ON work_order_material (ent_code, order_no);

CREATE TABLE IF NOT EXISTS work_order_routing (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    step_no         INT          NOT NULL,
    step_name       VARCHAR(64)  NOT NULL,
    work_center     VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_work_order_routing_ent ON work_order_routing (ent_code, order_no);

-- ---------------------------------------------------------------------------
-- 质检模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_inspection (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    inspection_no   VARCHAR(64)  NOT NULL,
    batch_no        VARCHAR(64),
    product_id      BIGINT       NOT NULL,
    product_name    VARCHAR(128),
    sample_qty      NUMERIC(18,3) NOT NULL DEFAULT 0,
    qualified_qty   NUMERIC(18,3) NOT NULL DEFAULT 0,
    defect_qty      NUMERIC(18,3) NOT NULL DEFAULT 0,
    inspection_date DATE         NOT NULL,
    result          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_inspection_no ON quality_inspection (ent_code, inspection_no);
CREATE INDEX IF NOT EXISTS idx_quality_inspection_ent_date ON quality_inspection (ent_code, inspection_date);

CREATE TABLE IF NOT EXISTS quality_defect_detail (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    inspection_no   VARCHAR(64)  NOT NULL,
    defect_type     VARCHAR(64)  NOT NULL,
    defect_qty      NUMERIC(18,3) NOT NULL DEFAULT 0,
    remark          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_quality_defect_ent ON quality_defect_detail (ent_code, inspection_no);

-- ---------------------------------------------------------------------------
-- 售后模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS after_sales_ticket (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    ticket_no       VARCHAR(64)  NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(128),
    product_id      BIGINT,
    product_name    VARCHAR(128),
    problem_type    VARCHAR(64),
    ticket_date     DATE         NOT NULL,
    handle_status   VARCHAR(16)  NOT NULL DEFAULT 'pending',
    description     VARCHAR(1024),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_after_sales_ticket_no ON after_sales_ticket (ent_code, ticket_no);
CREATE INDEX IF NOT EXISTS idx_after_sales_ticket_ent_date ON after_sales_ticket (ent_code, ticket_date);

CREATE TABLE IF NOT EXISTS return_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    return_no       VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64),
    customer_name   VARCHAR(128),
    product_name    VARCHAR(128),
    return_type     VARCHAR(16)  NOT NULL DEFAULT 'return',
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    return_date     DATE         NOT NULL,
    reason          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_return_order_no ON return_order (ent_code, return_no);
CREATE INDEX IF NOT EXISTS idx_return_order_ent_date ON return_order (ent_code, return_date);

-- ---------------------------------------------------------------------------
-- 财务模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS finance_ledger (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    ledger_date     DATE         NOT NULL,
    account_item    VARCHAR(64)  NOT NULL,
    income_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    expense_amount  NUMERIC(18,2) NOT NULL DEFAULT 0,
    counterparty    VARCHAR(128),
    remark          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_finance_ledger_ent_date ON finance_ledger (ent_code, ledger_date);

CREATE TABLE IF NOT EXISTS payment_record (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    payment_no      VARCHAR(64)  NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(128),
    order_no        VARCHAR(64),
    payment_date    DATE         NOT NULL,
    amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    payment_method  VARCHAR(32),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_record_no ON payment_record (ent_code, payment_no);
CREATE INDEX IF NOT EXISTS idx_payment_record_ent_date ON payment_record (ent_code, payment_date);

-- ---------------------------------------------------------------------------
-- 委外模块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outsourcing_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    supplier_id     BIGINT,
    supplier_name   VARCHAR(128),
    product_id      BIGINT,
    product_name    VARCHAR(128),
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    order_date      DATE         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'processing',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_outsourcing_order_no ON outsourcing_order (ent_code, order_no);
CREATE INDEX IF NOT EXISTS idx_outsourcing_order_ent_date ON outsourcing_order (ent_code, order_date);

CREATE TABLE IF NOT EXISTS outsourcing_material_flow (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    product_id      BIGINT,
    product_name    VARCHAR(128),
    flow_type       VARCHAR(16)  NOT NULL,
    quantity        NUMERIC(18,3) NOT NULL DEFAULT 0,
    flow_date       DATE         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_outsourcing_flow_ent ON outsourcing_material_flow (ent_code, order_no);
