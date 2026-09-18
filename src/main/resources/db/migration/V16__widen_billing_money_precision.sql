-- =============================================================================
-- 计费模块金额精度：NUMERIC(18,2) → NUMERIC(18,6)
-- =============================================================================
-- 起因：单价按「每千 token」定义（billing_price_rule 一开始就是 6 位小数），
-- 而金额侧却是 2 位。单次对话消耗常在 0.001 ~ 0.01 元量级，
-- 按「分」舍入后必然被抹成 0.00 —— 表现为：
--   · 余额永不减少（等于白用）
--   · 流水里堆积一堆 amount = 0.00 的扣费记录
--
-- 修法：金额精度与单价对齐到 6 位。**钱按真实值记账，"分"只是展示与结算单位**，
-- 展示与开票时再格式化回 2 位。
--
-- 范围说明：只改计费模块。
--   · billing_price_rule 的 input_price / output_price 本来就是 6 位，无需改动；
--   · 业务表（销售订单、采购单、库存等）的金额**保持 2 位** —— 那是整单金额，
--     「分」是合适的粒度，且要与财务对账口径一致。改成 6 位反而制造无意义的小数位。
-- =============================================================================

ALTER TABLE billing_plan
    ALTER COLUMN price TYPE NUMERIC(18, 6);

ALTER TABLE billing_account
    ALTER COLUMN balance TYPE NUMERIC(18, 6);

ALTER TABLE billing_transaction
    ALTER COLUMN amount TYPE NUMERIC(18, 6);

ALTER TABLE billing_transaction
    ALTER COLUMN balance_after TYPE NUMERIC(18, 6);

ALTER TABLE billing_invoice
    ALTER COLUMN total_amount TYPE NUMERIC(18, 6);
