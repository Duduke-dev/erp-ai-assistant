-- =============================================================================
-- V17 演示业务数据（DEMO 租户）
-- =============================================================================
-- 为什么需要这份数据：
--   V3 只插了租户 / 角色 / 账号，**业务表全空**。结果是 39 个业务 Tool 只能返回空结果，
--   AI「查业务数据」这条核心能力无从演示，前端各页也是空的。
--   这里按 8 个业务域铺一批可信的数据，让「问一句话拿到真实数字」成立。
--
-- 设计要点：
--   1. 关联列（customer_id / product_id / supplier_id）一律用**子查询**取真实主键，
--      不硬编码 id——id 是 GENERATED ALWAYS AS IDENTITY，硬编码会在已有数据的库上错位；
--   2. 日期铺在 2026-07 ~ 2026-09，使「本月/上月」这类相对时间提问有结果；
--   3. **刻意留了几条低库存与逾期应收**，用于验证预警类问题（否则这类问题恒为空）；
--   4. 全部 ON CONFLICT DO NOTHING，重复执行不会破坏既有数据。
--
-- 注：本脚本只针对 DEMO 租户，不touch 其他租户。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 物料主数据（20 条）
-- ---------------------------------------------------------------------------
INSERT INTO product (ent_code, product_code, product_name, spec, unit, category, safety_stock, unit_price, status)
VALUES
    ('DEMO', 'M001', '深沟球轴承', '6205-2RS', '个', '标准件', 200, 18.50, 'active'),
    ('DEMO', 'M002', '伺服电机', '750W/220V', '台', '电气件', 20, 1850.00, 'active'),
    ('DEMO', 'M003', '滚珠丝杆', 'SFU1605-L500', '根', '传动件', 50, 320.00, 'active'),
    ('DEMO', 'M004', '直线导轨', 'HGR20-1000', '根', '传动件', 40, 480.00, 'active'),
    ('DEMO', 'M005', 'PLC 控制器', 'FX3U-32MR', '台', '电气件', 15, 2400.00, 'active'),
    ('DEMO', 'M006', '变频器', '2.2KW', '台', '电气件', 18, 1350.00, 'active'),
    ('DEMO', 'M007', '气动夹爪', 'MHZ2-20D', '个', '气动件', 60, 265.00, 'active'),
    ('DEMO', 'M008', '气缸', 'SC63X100', '个', '气动件', 80, 145.00, 'active'),
    ('DEMO', 'M009', '同步带', 'HTD-5M-800', '条', '传动件', 120, 42.00, 'active'),
    ('DEMO', 'M010', '联轴器', 'LX3-25', '个', '传动件', 90, 88.00, 'active'),
    ('DEMO', 'M011', '铝型材', '4040-6米', '根', '结构件', 150, 96.00, 'active'),
    ('DEMO', 'M012', '钣金机架', '定制-CX200', '套', '结构件', 10, 3200.00, 'active'),
    ('DEMO', 'M013', '主轴总成', 'CX200-SP', '套', '核心件', 8, 12500.00, 'active'),
    ('DEMO', 'M014', '刀塔组件', '8工位', '套', '核心件', 6, 8600.00, 'active'),
    ('DEMO', 'M015', '冷却泵', 'CP-40', '台', '辅件', 25, 460.00, 'active'),
    ('DEMO', 'M016', '润滑油泵', 'LUB-2L', '台', '辅件', 30, 380.00, 'active'),
    ('DEMO', 'M017', '接近开关', 'LJ12A3-4-Z/BX', '个', '电气件', 200, 26.00, 'active'),
    ('DEMO', 'M018', '编码器', 'E6B2-CWZ6C', '个', '电气件', 45, 320.00, 'active'),
    ('DEMO', 'M019', '密封圈', 'NBR-45X62X8', '个', '标准件', 500, 3.20, 'active'),
    ('DEMO', 'M020', '紧固螺栓', 'M8X30-8.8级', '个', '标准件', 2000, 0.85, 'active')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 客户与供应商
-- ---------------------------------------------------------------------------
INSERT INTO customer (ent_code, customer_code, customer_name, contact_person, contact_phone, region, credit_limit, status)
VALUES
    ('DEMO', 'C001', '苏州精工机械有限公司', '张伟', '0512-66881234', '华东', 500000.00, 'active'),
    ('DEMO', 'C002', '东莞恒信自动化设备厂', '李明', '0769-88123456', '华南', 300000.00, 'active'),
    ('DEMO', 'C003', '宁波海天精密制造', '王强', '0574-87654321', '华东', 800000.00, 'active'),
    ('DEMO', 'C004', '天津重工装备集团', '刘洋', '022-23456789', '华北', 1200000.00, 'active'),
    ('DEMO', 'C005', '成都西部数控科技', '陈静', '028-86543210', '西南', 250000.00, 'active'),
    ('DEMO', 'C006', '武汉长江机床销售', '赵磊', '027-87654321', '华中', 400000.00, 'active'),
    ('DEMO', 'C007', '青岛海联工业设备', '孙丽', '0532-88889999', '华东', 350000.00, 'active'),
    ('DEMO', 'C008', '西安秦川精机', '周涛', '029-88887777', '西北', 200000.00, 'active')
ON CONFLICT DO NOTHING;

INSERT INTO supplier (ent_code, supplier_code, supplier_name, contact_person, contact_phone, status)
VALUES
    ('DEMO', 'S001', '上海哈轴贸易有限公司', '吴刚', '021-56781234', 'active'),
    ('DEMO', 'S002', '深圳汇川技术代理', '郑华', '0755-23456789', 'active'),
    ('DEMO', 'S003', '台湾上银科技（中国）', '林建宏', '021-61234567', 'active'),
    ('DEMO', 'S004', '浙江正泰电器供应', '徐敏', '0577-88112233', 'active'),
    ('DEMO', 'S005', '江苏亚德客气动件', '何军', '0519-88997766', 'active'),
    ('DEMO', 'S006', '广州市泰五金标准件', '马超', '020-81234567', 'active')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 库存（含刻意的低库存项，供「低库存预警」类问题使用）
-- ---------------------------------------------------------------------------
INSERT INTO inventory (ent_code, product_id, product_name, warehouse, location, batch_no, quantity, safety_stock)
SELECT 'DEMO', p.id, p.product_name, v.warehouse, v.location, v.batch_no, v.quantity, p.safety_stock
FROM (VALUES
    ('M001', '主仓', 'A-01-1-01', 'B240801', 680.000),
    ('M001', '成品仓', 'C-01-2-03', 'B240802', 120.000),
    ('M002', '主仓', 'A-02-1-05', 'B240715', 45.000),
    ('M002', '配件仓', 'D-01-1-02', 'B240716', 8.000),      -- 低于安全库存 20
    ('M003', '主仓', 'A-03-2-01', 'B240720', 96.000),
    ('M003', '配件仓', 'D-02-1-04', 'B240721', 22.000),     -- 低于安全库存 50
    ('M004', '主仓', 'A-03-2-08', 'B240722', 78.000),
    ('M005', '主仓', 'A-04-1-02', 'B240718', 32.000),
    ('M005', '电气仓', 'E-01-1-01', 'B240719', 6.000),      -- 低于安全库存 15
    ('M006', '电气仓', 'E-01-2-03', 'B240725', 41.000),
    ('M007', '主仓', 'A-05-1-06', 'B240728', 185.000),
    ('M008', '主仓', 'A-05-2-02', 'B240729', 240.000),
    ('M009', '配件仓', 'D-03-1-01', 'B240730', 320.000),
    ('M010', '配件仓', 'D-03-1-05', 'B240731', 175.000),
    ('M011', '原料仓', 'F-01-1-01', 'B240801', 420.000),
    ('M012', '成品仓', 'C-02-1-01', 'B240802', 24.000),
    ('M013', '成品仓', 'C-03-1-01', 'B240803', 12.000),
    ('M013', '主仓', 'A-06-1-01', 'B240804', 3.000),        -- 低于安全库存 8，核心件缺料
    ('M014', '成品仓', 'C-03-2-01', 'B240805', 14.000),
    ('M015', '辅件仓', 'G-01-1-01', 'B240806', 62.000),
    ('M016', '辅件仓', 'G-01-1-04', 'B240807', 88.000),
    ('M017', '电气仓', 'E-02-1-02', 'B240808', 850.000),
    ('M018', '电气仓', 'E-02-2-01', 'B240809', 120.000),
    ('M019', '标准件仓', 'H-01-1-01', 'B240810', 3200.000),
    ('M020', '标准件仓', 'H-01-2-01', 'B240811', 9800.000)
) AS v(code, warehouse, location, batch_no, quantity)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

-- ---------------------------------------------------------------------------
-- 销售订单（12 笔，铺在 7~9 月）+ 明细
-- ---------------------------------------------------------------------------
INSERT INTO sales_order (ent_code, order_no, customer_id, customer_name, order_date, delivery_date, total_amount, status, remark)
SELECT 'DEMO', v.order_no, c.id, c.customer_name, v.order_date::date, v.delivery_date::date, v.total_amount, v.status, v.remark
FROM (VALUES
    ('SO20260701', 'C001', '2026-07-05', '2026-07-20', 386000.00, 'completed', '首台 CX-200 整机'),
    ('SO20260702', 'C002', '2026-07-12', '2026-07-30', 128500.00, 'completed', NULL),
    ('SO20260703', 'C003', '2026-07-22', '2026-08-10', 642000.00, 'completed', '含两年维保'),
    ('SO20260801', 'C001', '2026-08-03', '2026-08-18', 215000.00, 'completed', '追加两条产线'),
    ('SO20260802', 'C004', '2026-08-08', '2026-08-28', 1240000.00, 'completed', '重工大单'),
    ('SO20260803', 'C005', '2026-08-15', '2026-09-02', 96000.00, 'completed', NULL),
    ('SO20260804', 'C006', '2026-08-21', '2026-09-08', 308000.00, 'completed', '客户指定配置'),
    ('SO20260805', 'C007', '2026-08-27', '2026-09-15', 174500.00, 'shipped', '已发货，待客户验收'),
    ('SO20260901', 'C003', '2026-09-02', '2026-09-20', 458000.00, 'confirmed', '月度框架协议'),
    ('SO20260902', 'C002', '2026-09-06', '2026-09-25', 132000.00, 'confirmed', NULL),
    ('SO20260903', 'C008', '2026-09-10', '2026-09-30', 87500.00, 'confirmed', '首单'),
    ('SO20260904', 'C004', '2026-09-14', '2026-10-08', 726000.00, 'draft', '待技术确认')
) AS v(order_no, customer_code, order_date, delivery_date, total_amount, status, remark)
JOIN customer c ON c.ent_code = 'DEMO' AND c.customer_code = v.customer_code;

INSERT INTO sales_order_item (ent_code, order_no, product_id, product_name, quantity, unit_price, amount)
SELECT 'DEMO', v.order_no, p.id, p.product_name, v.quantity, v.unit_price, v.amount
FROM (VALUES
    ('SO20260701', 'M013', 2.000, 12500.00, 25000.00),
    ('SO20260701', 'M012', 2.000, 3200.00, 6400.00),
    ('SO20260702', 'M003', 100.000, 320.00, 32000.00),
    ('SO20260702', 'M004', 80.000, 480.00, 38400.00),
    ('SO20260703', 'M013', 4.000, 12500.00, 50000.00),
    ('SO20260703', 'M014', 4.000, 8600.00, 34400.00),
    ('SO20260801', 'M002', 20.000, 1850.00, 37000.00),
    ('SO20260801', 'M005', 10.000, 2400.00, 24000.00),
    ('SO20260802', 'M013', 8.000, 12500.00, 100000.00),
    ('SO20260802', 'M014', 8.000, 8600.00, 68800.00),
    ('SO20260803', 'M007', 60.000, 265.00, 15900.00),
    ('SO20260803', 'M008', 80.000, 145.00, 11600.00),
    ('SO20260804', 'M001', 800.000, 18.50, 14800.00),
    ('SO20260804', 'M020', 4000.000, 0.85, 3400.00),
    ('SO20260805', 'M006', 18.000, 1350.00, 24300.00),
    ('SO20260805', 'M018', 45.000, 320.00, 14400.00),
    ('SO20260901', 'M013', 3.000, 12500.00, 37500.00),
    ('SO20260901', 'M015', 25.000, 460.00, 11500.00),
    ('SO20260902', 'M003', 60.000, 320.00, 19200.00),
    ('SO20260902', 'M009', 120.000, 42.00, 5040.00),
    ('SO20260903', 'M010', 90.000, 88.00, 7920.00),
    ('SO20260903', 'M017', 200.000, 26.00, 5200.00),
    ('SO20260904', 'M013', 5.000, 12500.00, 62500.00),
    ('SO20260904', 'M016', 30.000, 380.00, 11400.00)
) AS v(order_no, code, quantity, unit_price, amount)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

-- ---------------------------------------------------------------------------
-- 发货与应收
-- ---------------------------------------------------------------------------
INSERT INTO shipment (ent_code, shipment_no, order_no, shipment_date, quantity, logistics_no, status)
VALUES
    ('DEMO', 'SH20260701', 'SO20260701', '2026-07-18', 4.000, 'SF1234567890', 'delivered'),
    ('DEMO', 'SH20260702', 'SO20260702', '2026-07-28', 180.000, 'SF1234567891', 'delivered'),
    ('DEMO', 'SH20260703', 'SO20260703', '2026-08-08', 8.000, 'JD9876543210', 'delivered'),
    ('DEMO', 'SH20260801', 'SO20260801', '2026-08-16', 30.000, 'JD9876543211', 'delivered'),
    ('DEMO', 'SH20260802', 'SO20260802', '2026-08-26', 16.000, 'DB5566778899', 'delivered'),
    ('DEMO', 'SH20260803', 'SO20260803', '2026-08-31', 140.000, 'SF1234567892', 'delivered'),
    ('DEMO', 'SH20260804', 'SO20260804', '2026-09-06', 63.000, 'SF1234567893', 'in_transit'),
    ('DEMO', 'SH20260805', 'SO20260805', '2026-09-13', 28.000, 'JD9876543212', 'pending')
ON CONFLICT DO NOTHING;

INSERT INTO accounts_receivable (ent_code, customer_id, customer_name, order_no, due_amount, received_amount, due_date, aging_days, status)
SELECT 'DEMO', c.id, c.customer_name, v.order_no, v.due_amount, v.received_amount, v.due_date::date, v.aging_days, v.status
FROM (VALUES
    ('SO20260701', 'C001', 386000.00, 386000.00, '2026-08-20', 0, 'paid'),
    ('SO20260702', 'C002', 128500.00, 128500.00, '2026-08-30', 0, 'paid'),
    ('SO20260703', 'C003', 642000.00, 400000.00, '2026-09-10', 8, 'partial'),
    ('SO20260801', 'C001', 215000.00, 215000.00, '2026-09-18', 0, 'paid'),
    ('SO20260802', 'C004', 1240000.00, 600000.00, '2026-09-28', 0, 'partial'),
    ('SO20260803', 'C005', 96000.00, 0.00, '2026-09-02', 16, 'overdue'),
    ('SO20260804', 'C006', 308000.00, 0.00, '2026-09-08', 10, 'overdue'),
    ('SO20260805', 'C007', 174500.00, 0.00, '2026-10-15', 0, 'unpaid'),
    ('SO20260901', 'C003', 458000.00, 0.00, '2026-10-20', 0, 'unpaid'),
    ('SO20260902', 'C002', 132000.00, 0.00, '2026-10-25', 0, 'unpaid')
) AS v(order_no, customer_code, due_amount, received_amount, due_date, aging_days, status)
JOIN customer c ON c.ent_code = 'DEMO' AND c.customer_code = v.customer_code;

-- ---------------------------------------------------------------------------
-- 采购订单（8 笔）+ 明细 + 收货 + 应付
-- ---------------------------------------------------------------------------
INSERT INTO purchase_order (ent_code, order_no, supplier_id, supplier_name, order_date, total_amount, status)
SELECT 'DEMO', v.order_no, s.id, s.supplier_name, v.order_date::date, v.total_amount, v.status
FROM (VALUES
    ('PO20260701', 'S001', '2026-07-02', 7400.00, 'received'),
    ('PO20260702', 'S003', '2026-07-10', 16000.00, 'received'),
    ('PO20260703', 'S002', '2026-07-18', 37000.00, 'received'),
    ('PO20260801', 'S004', '2026-08-05', 24300.00, 'received'),
    ('PO20260802', 'S005', '2026-08-12', 14500.00, 'received'),
    ('PO20260803', 'S006', '2026-08-20', 8500.00, 'partial'),
    ('PO20260901', 'S003', '2026-09-03', 24000.00, 'confirmed'),
    ('PO20260902', 'S001', '2026-09-12', 11100.00, 'draft')
) AS v(order_no, supplier_code, order_date, total_amount, status)
JOIN supplier s ON s.ent_code = 'DEMO' AND s.supplier_code = v.supplier_code;

INSERT INTO purchase_order_item (ent_code, order_no, product_id, product_name, quantity, received_qty, unit_price, amount)
SELECT 'DEMO', v.order_no, p.id, p.product_name, v.quantity, v.received_qty, v.unit_price, v.amount
FROM (VALUES
    ('PO20260701', 'M001', 400.000, 400.000, 18.50, 7400.00),
    ('PO20260702', 'M003', 30.000, 30.000, 320.00, 9600.00),
    ('PO20260702', 'M004', 15.000, 15.000, 480.00, 7200.00),
    ('PO20260703', 'M002', 20.000, 20.000, 1850.00, 37000.00),
    ('PO20260801', 'M006', 18.000, 18.000, 1350.00, 24300.00),
    ('PO20260802', 'M007', 40.000, 40.000, 265.00, 10600.00),
    ('PO20260802', 'M008', 30.000, 30.000, 145.00, 4350.00),
    ('PO20260803', 'M019', 2000.000, 1200.000, 3.20, 6400.00),
    ('PO20260803', 'M020', 3000.000, 1200.000, 0.85, 2550.00),
    ('PO20260901', 'M013', 2.000, 0.000, 12500.00, 25000.00)
) AS v(order_no, code, quantity, received_qty, unit_price, amount)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO purchase_receive (ent_code, receive_no, order_no, product_id, product_name, receive_date, quantity)
SELECT 'DEMO', v.receive_no, v.order_no, p.id, p.product_name, v.receive_date::date, v.quantity
FROM (VALUES
    ('RC20260701', 'PO20260701', 'M001', '2026-07-15', 400.000),
    ('RC20260702', 'PO20260702', 'M003', '2026-07-22', 30.000),
    ('RC20260703', 'PO20260702', 'M004', '2026-07-22', 15.000),
    ('RC20260704', 'PO20260703', 'M002', '2026-07-30', 20.000),
    ('RC20260801', 'PO20260801', 'M006', '2026-08-18', 18.000),
    ('RC20260802', 'PO20260802', 'M007', '2026-08-25', 40.000),
    ('RC20260803', 'PO20260803', 'M019', '2026-09-02', 1200.000)
) AS v(receive_no, order_no, code, receive_date, quantity)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO accounts_payable (ent_code, supplier_id, supplier_name, order_no, due_amount, paid_amount, due_date, aging_days, status)
SELECT 'DEMO', s.id, s.supplier_name, v.order_no, v.due_amount, v.paid_amount, v.due_date::date, v.aging_days, v.status
FROM (VALUES
    ('PO20260701', 'S001', 7400.00, 7400.00, '2026-08-15', 0, 'paid'),
    ('PO20260702', 'S003', 16000.00, 16000.00, '2026-08-22', 0, 'paid'),
    ('PO20260703', 'S002', 37000.00, 37000.00, '2026-08-30', 0, 'paid'),
    ('PO20260801', 'S004', 24300.00, 24300.00, '2026-09-18', 0, 'paid'),
    ('PO20260802', 'S005', 14500.00, 14500.00, '2026-09-25', 0, 'paid'),
    ('PO20260803', 'S006', 8500.00, 0.00, '2026-09-05', 13, 'overdue'),
    ('PO20260901', 'S003', 24000.00, 0.00, '2026-10-15', 0, 'unpaid')
) AS v(order_no, supplier_code, due_amount, paid_amount, due_date, aging_days, status)
JOIN supplier s ON s.ent_code = 'DEMO' AND s.supplier_code = v.supplier_code;

-- ---------------------------------------------------------------------------
-- 出入库流水（近 30 条）
-- ---------------------------------------------------------------------------
INSERT INTO stock_movement (ent_code, movement_no, product_id, product_name, warehouse, movement_type, quantity, movement_date, related_no)
SELECT 'DEMO', v.movement_no, p.id, p.product_name, v.warehouse, v.movement_type, v.quantity,
       v.movement_date::date, v.related_no
FROM (VALUES
    ('SM202609001', 'M001', '主仓', 'in', 400.000, '2026-09-01', 'RC20260701'),
    ('SM202609002', 'M002', '主仓', 'in', 20.000, '2026-09-01', 'RC20260704'),
    ('SM202609003', 'M013', '成品仓', 'out', 2.000, '2026-09-02', 'SO20260701'),
    ('SM202609004', 'M007', '主仓', 'in', 40.000, '2026-09-02', 'RC20260802'),
    ('SM202609005', 'M003', '主仓', 'out', 12.000, '2026-09-03', 'WO20260901'),
    ('SM202609006', 'M004', '主仓', 'out', 8.000, '2026-09-03', 'WO20260901'),
    ('SM202609007', 'M019', '标准件仓', 'in', 1200.000, '2026-09-04', 'RC20260803'),
    ('SM202609008', 'M020', '标准件仓', 'out', 600.000, '2026-09-05', 'WO20260902'),
    ('SM202609009', 'M005', '电气仓', 'out', 4.000, '2026-09-06', 'WO20260902'),
    ('SM202609010', 'M018', '电气仓', 'in', 60.000, '2026-09-07', 'PO20260901'),
    ('SM202609011', 'M009', '配件仓', 'out', 40.000, '2026-09-08', 'WO20260903'),
    ('SM202609012', 'M010', '配件仓', 'out', 30.000, '2026-09-08', 'WO20260903'),
    ('SM202609013', 'M013', '主仓', 'out', 1.000, '2026-09-09', 'WO20260904'),
    ('SM202609014', 'M011', '原料仓', 'in', 120.000, '2026-09-10', 'PO20260901'),
    ('SM202609015', 'M015', '辅件仓', 'out', 6.000, '2026-09-11', 'WO20260904'),
    ('SM202609016', 'M016', '辅件仓', 'out', 8.000, '2026-09-11', 'WO20260904'),
    ('SM202609017', 'M017', '电气仓', 'out', 45.000, '2026-09-12', 'WO20260905'),
    ('SM202609018', 'M001', '主仓', 'out', 80.000, '2026-09-13', 'SO20260804'),
    ('SM202609019', 'M012', '成品仓', 'in', 4.000, '2026-09-14', 'WO20260905'),
    ('SM202609020', 'M014', '成品仓', 'in', 2.000, '2026-09-14', 'WO20260905'),
    ('SM202609021', 'M006', '电气仓', 'out', 3.000, '2026-09-15', 'WO20260906'),
    ('SM202609022', 'M008', '主仓', 'in', 30.000, '2026-09-15', 'RC20260802'),
    ('SM202609023', 'M003', '配件仓', 'in', 10.000, '2026-09-16', 'PO20260702'),
    ('SM202609024', 'M004', '主仓', 'in', 6.000, '2026-09-16', 'PO20260702'),
    ('SM202609025', 'M019', '标准件仓', 'out', 400.000, '2026-09-17', 'WO20260906'),
    ('SM202609026', 'M020', '标准件仓', 'out', 1200.000, '2026-09-17', 'WO20260906'),
    ('SM202609027', 'M010', '配件仓', 'in', 20.000, '2026-09-17', 'PO20260902'),
    ('SM202609028', 'M002', '配件仓', 'out', 2.000, '2026-09-18', 'WO20260907')
) AS v(movement_no, code, warehouse, movement_type, quantity, movement_date, related_no)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

-- ---------------------------------------------------------------------------
-- 生产工单（10 个）+ 用料 + 工艺路线
-- ---------------------------------------------------------------------------
INSERT INTO work_order (ent_code, order_no, product_id, product_name, planned_qty, completed_qty, scrap_qty,
                        plan_start_date, plan_end_date, status)
SELECT 'DEMO', v.order_no, p.id, p.product_name, v.planned_qty, v.completed_qty, v.scrap_qty,
       v.start_date::date, v.end_date::date, v.status
FROM (VALUES
    ('WO20260901', 'M003', 30.000, 30.000, 1.000, '2026-09-01', '2026-09-05', 'completed'),
    ('WO20260902', 'M005', 10.000, 10.000, 0.000, '2026-09-02', '2026-09-06', 'completed'),
    ('WO20260903', 'M009', 40.000, 40.000, 2.000, '2026-09-03', '2026-09-08', 'completed'),
    ('WO20260904', 'M013', 2.000, 2.000, 0.000, '2026-09-04', '2026-09-12', 'completed'),
    ('WO20260905', 'M012', 4.000, 3.000, 0.000, '2026-09-08', '2026-09-16', 'processing'),
    ('WO20260906', 'M006', 8.000, 5.000, 1.000, '2026-09-10', '2026-09-20', 'processing'),
    ('WO20260907', 'M002', 6.000, 0.000, 0.000, '2026-09-15', '2026-09-25', 'processing'),
    ('WO20260908', 'M014', 3.000, 0.000, 0.000, '2026-09-18', '2026-09-28', 'planned'),
    ('WO20260909', 'M015', 10.000, 0.000, 0.000, '2026-09-20', '2026-09-30', 'planned'),
    ('WO20260910', 'M017', 200.000, 0.000, 0.000, '2026-09-22', '2026-10-05', 'planned')
) AS v(order_no, code, planned_qty, completed_qty, scrap_qty, start_date, end_date, status)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO work_order_material (ent_code, order_no, product_id, product_name, required_qty, issued_qty)
SELECT 'DEMO', v.order_no, p.id, p.product_name, v.required_qty, v.issued_qty
FROM (VALUES
    ('WO20260901', 'M003', 30.000, 30.000),
    ('WO20260901', 'M004', 30.000, 30.000),
    ('WO20260902', 'M005', 10.000, 10.000),
    ('WO20260902', 'M017', 40.000, 40.000),
    ('WO20260903', 'M009', 40.000, 40.000),
    ('WO20260904', 'M013', 2.000, 2.000),
    ('WO20260904', 'M019', 16.000, 16.000),
    ('WO20260905', 'M011', 80.000, 80.000),
    ('WO20260905', 'M012', 4.000, 4.000),
    ('WO20260906', 'M006', 8.000, 5.000),
    ('WO20260907', 'M002', 6.000, 0.000),
    ('WO20260908', 'M014', 3.000, 0.000)
) AS v(order_no, code, required_qty, issued_qty)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO work_order_routing (ent_code, order_no, step_no, step_name, work_center, status)
VALUES
    ('DEMO', 'WO20260901', 10, '下料', '下料车间', 'completed'),
    ('DEMO', 'WO20260901', 20, '粗车', '机加工一车间', 'completed'),
    ('DEMO', 'WO20260901', 30, '精磨', '精密车间', 'completed'),
    ('DEMO', 'WO20260902', 10, '装配', '装配一车间', 'completed'),
    ('DEMO', 'WO20260902', 20, '调试', '调试中心', 'completed'),
    ('DEMO', 'WO20260904', 10, '主轴装配', '精密车间', 'completed'),
    ('DEMO', 'WO20260904', 20, '动平衡', '精密车间', 'completed'),
    ('DEMO', 'WO20260904', 30, '精度检测', '质检中心', 'completed'),
    ('DEMO', 'WO20260905', 10, '钣金折弯', '钣金车间', 'completed'),
    ('DEMO', 'WO20260905', 20, '焊接', '焊接车间', 'processing'),
    ('DEMO', 'WO20260905', 30, '喷涂', '涂装车间', 'pending'),
    ('DEMO', 'WO20260906', 10, '电气装配', '装配二车间', 'processing'),
    ('DEMO', 'WO20260906', 20, '通电测试', '调试中心', 'pending'),
    ('DEMO', 'WO20260907', 10, '电机装配', '装配一车间', 'pending'),
    ('DEMO', 'WO20260908', 10, '刀塔装配', '精密车间', 'pending')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 质量检验（12 条）+ 缺陷明细
-- ---------------------------------------------------------------------------
INSERT INTO quality_inspection (ent_code, inspection_no, batch_no, product_id, product_name,
                                sample_qty, qualified_qty, defect_qty, inspection_date, result)
SELECT 'DEMO', v.inspection_no, v.batch_no, p.id, p.product_name,
       v.sample_qty, v.qualified_qty, v.defect_qty, v.inspection_date::date, v.result
FROM (VALUES
    ('QI202609001', 'B240901', 'M001', 50.000, 49.000, 1.000, '2026-09-01', 'pass'),
    ('QI202609002', 'B240902', 'M003', 30.000, 30.000, 0.000, '2026-09-02', 'pass'),
    ('QI202609003', 'B240903', 'M007', 40.000, 38.000, 2.000, '2026-09-03', 'pass'),
    ('QI202609004', 'B240904', 'M013', 2.000, 2.000, 0.000, '2026-09-05', 'pass'),
    ('QI202609005', 'B240905', 'M019', 100.000, 97.000, 3.000, '2026-09-07', 'pass'),
    ('QI202609006', 'B240906', 'M005', 10.000, 9.000, 1.000, '2026-09-09', 'pass'),
    ('QI202609007', 'B240907', 'M011', 60.000, 54.000, 6.000, '2026-09-11', 'fail'),
    ('QI202609008', 'B240908', 'M009', 40.000, 40.000, 0.000, '2026-09-13', 'pass'),
    ('QI202609009', 'B240909', 'M006', 8.000, 7.000, 1.000, '2026-09-15', 'pass'),
    ('QI202609010', 'B240910', 'M020', 200.000, 196.000, 4.000, '2026-09-16', 'pass'),
    ('QI202609011', 'B240911', 'M002', 6.000, 6.000, 0.000, '2026-09-17', 'pass'),
    ('QI202609012', 'B240912', 'M012', 4.000, 3.000, 1.000, '2026-09-18', 'pass')
) AS v(inspection_no, batch_no, code, sample_qty, qualified_qty, defect_qty, inspection_date, result)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO quality_defect_detail (ent_code, inspection_no, defect_type, defect_qty, remark)
VALUES
    ('DEMO', 'QI202609001', '尺寸超差', 1.000, '外径超出公差上限 0.01mm'),
    ('DEMO', 'QI202609003', '表面划伤', 2.000, '运输过程中造成'),
    ('DEMO', 'QI202609005', '硬度不足', 3.000, '热处理批次异常'),
    ('DEMO', 'QI202609006', '装配干涉', 1.000, '接线端子位置偏移'),
    ('DEMO', 'QI202609007', '折弯角度偏差', 6.000, '模具磨损导致，已安排修模'),
    ('DEMO', 'QI202609009', '电气参数漂移', 1.000, '输出电压偏差 3%'),
    ('DEMO', 'QI202609010', '螺纹瑕疵', 4.000, '搓丝模具需更换'),
    ('DEMO', 'QI202609012', '喷涂色差', 1.000, '与样板比对偏差较大')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 售后工单（8 条）+ 退货
-- ---------------------------------------------------------------------------
INSERT INTO after_sales_ticket (ent_code, ticket_no, customer_id, customer_name, product_id, product_name,
                                problem_type, ticket_date, handle_status, description)
SELECT 'DEMO', v.ticket_no, c.id, c.customer_name, p.id, p.product_name,
       v.problem_type, v.ticket_date::date, v.handle_status, v.description
FROM (VALUES
    ('AS202609001', 'C001', 'M013', '主轴异响', '2026-09-02', 'resolved', '主轴运转时有轻微金属摩擦声，已更换轴承并重新动平衡'),
    ('AS202609002', 'C002', 'M005', '控制器报警', '2026-09-05', 'resolved', 'PLC 报 E-021 故障码，为接线松动'),
    ('AS202609003', 'C004', 'M006', '变频器过热', '2026-09-08', 'processing', '连续运行 6 小时后触发过温保护，待现场确认散热条件'),
    ('AS202609004', 'C003', 'M007', '夹爪不动作', '2026-09-10', 'resolved', '气路堵塞，已清理并更换过滤器'),
    ('AS202609005', 'C005', 'M003', '丝杆精度超差', '2026-09-12', 'processing', '定位精度偏差 0.02mm，待检测确认'),
    ('AS202609006', 'C006', 'M018', '编码器信号丢失', '2026-09-14', 'resolved', '屏蔽线接地不良，已重新布线'),
    ('AS202609007', 'C007', 'M002', '电机温升偏高', '2026-09-16', 'pending', '待安排工程师上门检测'),
    ('AS202609008', 'C008', 'M015', '冷却泵漏水', '2026-09-17', 'pending', '密封件老化，待发备件')
) AS v(ticket_no, customer_code, code, problem_type, ticket_date, handle_status, description)
JOIN customer c ON c.ent_code = 'DEMO' AND c.customer_code = v.customer_code
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO return_order (ent_code, return_no, order_no, customer_name, product_name, return_type, quantity, return_date, reason)
VALUES
    ('DEMO', 'RT202609001', 'SO20260801', '苏州精工机械有限公司', '伺服电机', 'return', 2.000, '2026-09-06', '客户选型错误，更换为更大功率型号'),
    ('DEMO', 'RT202609002', 'SO20260804', '武汉长江机床销售', '接近开关', 'return', 12.000, '2026-09-12', '来料批次不良，已退供应商'),
    ('DEMO', 'RT202609003', 'SO20260702', '东莞恒信自动化设备厂', '滚珠丝杆', 'exchange', 1.000, '2026-09-15', '精度不达标，换货处理')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 财务：总账（近两个月）+ 收款记录
-- ---------------------------------------------------------------------------
INSERT INTO finance_ledger (ent_code, ledger_date, account_item, income_amount, expense_amount, counterparty, remark)
VALUES
    ('DEMO', '2026-08-31', '主营业务收入', 1687000.00, 0.00, '销售汇总', '8 月销售收入结转'),
    ('DEMO', '2026-08-31', '材料采购', 0.00, 46800.00, '采购汇总', '8 月材料采购'),
    ('DEMO', '2026-08-31', '人工成本', 0.00, 236000.00, '生产部', '8 月工资及社保'),
    ('DEMO', '2026-08-31', '制造费用', 0.00, 88500.00, '车间归集', '水电、折旧、辅料'),
    ('DEMO', '2026-09-05', '主营业务收入', 386000.00, 0.00, '苏州精工机械有限公司', 'SO20260701 回款'),
    ('DEMO', '2026-09-08', '主营业务收入', 128500.00, 0.00, '东莞恒信自动化设备厂', 'SO20260702 回款'),
    ('DEMO', '2026-09-10', '材料采购', 0.00, 7400.00, '上海哈轴贸易有限公司', 'PO20260701 付款'),
    ('DEMO', '2026-09-12', '主营业务收入', 400000.00, 0.00, '宁波海天精密制造', 'SO20260703 部分回款'),
    ('DEMO', '2026-09-15', '材料采购', 0.00, 16000.00, '台湾上银科技（中国）', 'PO20260702 付款'),
    ('DEMO', '2026-09-18', '主营业务收入', 215000.00, 0.00, '苏州精工机械有限公司', 'SO20260801 回款'),
    ('DEMO', '2026-09-18', '人工成本', 0.00, 228000.00, '生产部', '9 月工资计提'),
    ('DEMO', '2026-09-18', '制造费用', 0.00, 76300.00, '车间归集', '9 月水电与折旧'),
    ('DEMO', '2026-09-18', '管理费用', 0.00, 54000.00, '综合管理部', '办公与差旅'),
    ('DEMO', '2026-09-18', '销售费用', 0.00, 42000.00, '销售部', '展会与客户拜访')
ON CONFLICT DO NOTHING;

INSERT INTO payment_record (ent_code, payment_no, customer_id, customer_name, order_no, payment_date, amount, payment_method)
SELECT 'DEMO', v.payment_no, c.id, c.customer_name, v.order_no, v.payment_date::date, v.amount, v.payment_method
FROM (VALUES
    ('PM202609001', 'C001', 'SO20260701', '2026-09-05', 386000.00, 'bank_transfer'),
    ('PM202609002', 'C002', 'SO20260702', '2026-09-08', 128500.00, 'bank_transfer'),
    ('PM202609003', 'C003', 'SO20260703', '2026-09-12', 400000.00, 'bank_transfer'),
    ('PM202609004', 'C001', 'SO20260801', '2026-09-18', 215000.00, 'bank_transfer'),
    ('PM202609005', 'C004', 'SO20260802', '2026-09-18', 600000.00, 'acceptance_bill'),
    ('PM202609006', 'C007', 'SO20260805', '2026-09-19', 50000.00, 'bank_transfer')
) AS v(payment_no, customer_code, order_no, payment_date, amount, payment_method)
JOIN customer c ON c.ent_code = 'DEMO' AND c.customer_code = v.customer_code;

-- ---------------------------------------------------------------------------
-- 委外加工（6 单）+ 物料流转
-- ---------------------------------------------------------------------------
INSERT INTO outsourcing_order (ent_code, order_no, supplier_id, supplier_name, product_id, product_name,
                               quantity, order_date, status)
SELECT 'DEMO', v.order_no, s.id, s.supplier_name, p.id, p.product_name, v.quantity, v.order_date::date, v.status
FROM (VALUES
    ('OS202608001', 'S006', 'M012', 6.000, '2026-08-06', 'completed'),
    ('OS202608002', 'S005', 'M008', 40.000, '2026-08-15', 'completed'),
    ('OS202609001', 'S006', 'M011', 60.000, '2026-09-04', 'processing'),
    ('OS202609002', 'S002', 'M018', 30.000, '2026-09-09', 'processing'),
    ('OS202609003', 'S004', 'M017', 150.000, '2026-09-14', 'processing'),
    ('OS202609004', 'S003', 'M003', 12.000, '2026-09-18', 'confirmed')
) AS v(order_no, supplier_code, code, quantity, order_date, status)
JOIN supplier s ON s.ent_code = 'DEMO' AND s.supplier_code = v.supplier_code
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;

INSERT INTO outsourcing_material_flow (ent_code, order_no, product_id, product_name, flow_type, quantity, flow_date)
SELECT 'DEMO', v.order_no, p.id, p.product_name, v.flow_type, v.quantity, v.flow_date::date
FROM (VALUES
    ('OS202608001', 'M011', 'out', 120.000, '2026-08-06'),
    ('OS202608001', 'M012', 'in', 6.000, '2026-08-28'),
    ('OS202608002', 'M008', 'out', 40.000, '2026-08-15'),
    ('OS202608002', 'M008', 'in', 38.000, '2026-09-02'),
    ('OS202609001', 'M011', 'out', 180.000, '2026-09-04'),
    ('OS202609002', 'M018', 'out', 30.000, '2026-09-09'),
    ('OS202609003', 'M017', 'out', 150.000, '2026-09-14'),
    ('OS202609004', 'M003', 'out', 12.000, '2026-09-18')
) AS v(order_no, code, flow_type, quantity, flow_date)
JOIN product p ON p.ent_code = 'DEMO' AND p.product_code = v.code;
