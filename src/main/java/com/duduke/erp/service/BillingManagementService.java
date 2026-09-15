package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.BillingAccountSaveDTO;
import com.duduke.erp.entity.dto.BillingPlanSaveDTO;
import com.duduke.erp.entity.dto.BillingPriceRuleSaveDTO;
import com.duduke.erp.entity.dto.BillingRechargeDTO;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.entity.po.BillingInvoice;
import com.duduke.erp.entity.po.BillingPlan;
import com.duduke.erp.entity.po.BillingPriceRule;
import com.duduke.erp.entity.po.BillingTransaction;
import com.duduke.erp.entity.vo.BillingInvoiceVO;
import com.duduke.erp.entity.vo.BillingPlanVO;
import com.duduke.erp.entity.vo.BillingPriceRuleVO;
import com.duduke.erp.entity.vo.BillingTransactionVO;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.mapper.BillingInvoiceMapper;
import com.duduke.erp.mapper.BillingPlanMapper;
import com.duduke.erp.mapper.BillingPriceRuleMapper;
import com.duduke.erp.mapper.BillingTransactionMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 计费管理端：套餐与价格规则的维护。
 * <p>
 * 两张表都是<b>平台级全局配置</b>（无 {@code ent_code}，已在 ignore-tables 中），
 * 因此这里的写操作对所有租户生效——权限只给 admin（{@code billing:manage}）。
 *
 * <h3>两条刻意的约束</h3>
 * <ol>
 *   <li><b>套餐编码不可改</b>：账户表用 {@code plan_code} 关联套餐，改编码会让既有账户
 *       指向一个不存在的套餐，而这在查询时不会报错，只是查不到。</li>
 *   <li><b>价格规则不给更新接口</b>：价格是带生效日期的历史记录，
 *       「改一条历史价格」没有正确语义——要调价就新增一条生效日期更晚的记录。
 *       同时禁止同模型同生效日出现两条，否则「取不晚于目标日期的最新一条」就没有唯一答案。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class BillingManagementService {

    private final BillingPlanMapper planMapper;

    private final BillingPriceRuleMapper priceRuleMapper;

    private final BillingAccountMapper accountMapper;

    private final BillingTransactionMapper transactionMapper;

    private final BillingInvoiceMapper invoiceMapper;

    // ===== 套餐 =====

    /** 全部套餐，按编码排序 */
    public List<BillingPlanVO> listPlans() {
        return this.planMapper.selectList(
                        Wrappers.<BillingPlan>lambdaQuery().orderByAsc(BillingPlan::getPlanCode))
                .stream().map(this::toPlanVO).toList();
    }

    public BillingPlanVO getPlan(Long id) {
        return toPlanVO(requirePlan(id));
    }

    /**
     * 新增套餐。
     *
     * @return 新套餐主键
     */
    public Long createPlan(BillingPlanSaveDTO dto) {
        String planCode = requireText(dto.planCode(), "套餐编码不能为空");
        BillingPlan existing = this.planMapper.selectOne(Wrappers.<BillingPlan>lambdaQuery()
                .eq(BillingPlan::getPlanCode, planCode).last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException("套餐编码已存在：" + planCode);
        }
        BillingPlan plan = new BillingPlan();
        plan.setPlanCode(planCode);
        plan.setPlanName(requireText(dto.planName(), "套餐名称不能为空"));
        plan.setMonthlyQuota(requireNonNegative(dto.monthlyQuota(), "月度配额不能为负"));
        plan.setPrice(requireNonNegative(dto.price(), "套餐价格不能为负"));
        this.planMapper.insert(plan);
        return plan.getId();
    }

    /**
     * 更新套餐。<b>不接受改编码</b>：即使请求里传了新编码也忽略，仍用原编码。
     */
    public void updatePlan(Long id, BillingPlanSaveDTO dto) {
        BillingPlan plan = requirePlan(id);
        plan.setPlanName(requireText(dto.planName(), "套餐名称不能为空"));
        plan.setMonthlyQuota(requireNonNegative(dto.monthlyQuota(), "月度配额不能为负"));
        plan.setPrice(requireNonNegative(dto.price(), "套餐价格不能为负"));
        this.planMapper.updateById(plan);
    }

    /**
     * 删除套餐。
     * <p>
     * <b>被任何租户的账户引用时拒绝</b>：套餐是全局配置，残留的 {@code plan_code}
     * 会让那些账户查不到套餐，且没有任何报错。引用检查必须跨租户，
     * 详见 {@link BillingAccountMapper#countByPlanCodeAcrossTenants}。
     */
    public void removePlan(Long id) {
        BillingPlan plan = requirePlan(id);
        long referenced = this.accountMapper.countByPlanCodeAcrossTenants(plan.getPlanCode());
        if (referenced > 0) {
            throw new BusinessException("该套餐仍被 " + referenced + " 个账户引用，不能删除");
        }
        this.planMapper.deleteById(id);
    }

    // ===== 价格规则 =====

    /**
     * 价格规则列表。
     *
     * @param modelName 按模型过滤；为空则返回全部
     */
    public List<BillingPriceRuleVO> listPriceRules(String modelName) {
        return this.priceRuleMapper.selectList(Wrappers.<BillingPriceRule>lambdaQuery()
                        .eq(StringUtils.hasText(modelName), BillingPriceRule::getModelName, modelName)
                        .orderByAsc(BillingPriceRule::getModelName)
                        .orderByDesc(BillingPriceRule::getEffectiveDate))
                .stream().map(this::toPriceRuleVO).toList();
    }

    /**
     * 新增价格规则（调价即新增一条更晚生效的记录）。
     *
     * @return 新记录主键
     */
    public Long createPriceRule(BillingPriceRuleSaveDTO dto) {
        String modelName = requireText(dto.modelName(), "模型名不能为空");
        if (dto.effectiveDate() == null) {
            throw new BusinessException("生效日期不能为空");
        }
        BillingPriceRule duplicate = this.priceRuleMapper.selectOne(
                Wrappers.<BillingPriceRule>lambdaQuery()
                        .eq(BillingPriceRule::getModelName, modelName)
                        .eq(BillingPriceRule::getEffectiveDate, dto.effectiveDate())
                        .last("LIMIT 1"));
        if (duplicate != null) {
            throw new BusinessException("该模型在此生效日期已有价格记录，请改用其它日期（调价请新增更晚的生效日）");
        }
        BillingPriceRule rule = new BillingPriceRule();
        rule.setModelName(modelName);
        rule.setInputPrice(requireNonNegative(dto.inputPrice(), "输入单价不能为负"));
        rule.setOutputPrice(requireNonNegative(dto.outputPrice(), "输出单价不能为负"));
        rule.setEffectiveDate(dto.effectiveDate());
        this.priceRuleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 删除价格规则。
     * <p>
     * 允许删除：历史账单存的是金额而非价格引用，删掉规则不会让已出账的金额变化。
     * 该接口主要用于纠正录错的记录。
     */
    public void removePriceRule(Long id) {
        if (this.priceRuleMapper.selectById(id) == null) {
            throw new BusinessException("价格规则不存在：" + id);
        }
        this.priceRuleMapper.deleteById(id);
    }

    // ===== 账户 =====

    private static final String TYPE_RECHARGE = "recharge";

    private static final String TYPE_DEDUCTION = "deduction";

    private static final String STATUS_ACTIVE = "active";

    private static final java.util.Set<String> ALLOWED_STATUS =
            java.util.Set.of("active", "suspended", "arrears");

    /**
     * 开户。每租户只允许一条账户。
     * <p>
     * <b>不指定额度时从套餐快照</b>：账户持有的是签约时的额度，
     * 套餐后来调额不该追溯改变已开户账户的当前周期额度。
     */
    public Long createAccount(BillingAccountSaveDTO dto) {
        if (currentAccount() != null) {
            throw new BusinessException("本租户已开户，请使用更新接口");
        }
        String planCode = requireText(dto.planCode(), "套餐编码不能为空");
        BillingPlan plan = requirePlanByCode(planCode);

        BillingAccount account = new BillingAccount();
        account.setPlanCode(planCode);
        account.setMonthlyQuota(dto.monthlyQuota() == null
                ? (plan.getMonthlyQuota() == null ? 0L : plan.getMonthlyQuota())
                : requireNonNegative(dto.monthlyQuota(), "月度配额不能为负"));
        account.setBalance(BigDecimal.ZERO);
        account.setUsedTokens(0L);
        account.setStatus(normalizeStatus(dto.status()));
        this.accountMapper.insert(account);
        return account.getId();
    }

    /**
     * 更新账户（套餐 / 配额 / 状态）。
     * <p>
     * <b>不动 {@code usedTokens}</b>：已用量只能由真实消耗累加，
     * 允许管理端改写会让用量统计变成可由人工覆盖的数字，失去对账意义。
     */
    public void updateAccount(Long id, BillingAccountSaveDTO dto) {
        BillingAccount account = requireAccount(id);
        String planCode = requireText(dto.planCode(), "套餐编码不能为空");
        BillingPlan plan = requirePlanByCode(planCode);

        account.setPlanCode(planCode);
        account.setMonthlyQuota(dto.monthlyQuota() == null
                ? (plan.getMonthlyQuota() == null ? 0L : plan.getMonthlyQuota())
                : requireNonNegative(dto.monthlyQuota(), "月度配额不能为负"));
        account.setStatus(normalizeStatus(dto.status()));
        this.accountMapper.updateById(account);
    }

    /**
     * 充值：加余额并记一条 recharge 流水。
     * <p>
     * <b>金额必须为正</b>：扣钱走系统内部的 deduction，不让一个接口同时承担
     * 加钱与扣钱两种语义——那正是财务接口最容易被误用的地方。
     */
    public void recharge(Long id, BillingRechargeDTO dto) {
        BillingAccount account = requireAccount(id);
        BigDecimal amount = dto.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("充值金额必须大于 0");
        }
        BigDecimal balanceAfter = value(account.getBalance()).add(amount);
        account.setBalance(balanceAfter);
        this.accountMapper.updateById(account);

        insertTransaction(TYPE_RECHARGE, amount, balanceAfter, 0L,
                StringUtils.hasText(dto.remark()) ? dto.remark().trim() : "账户充值");
    }

    // ===== 交易流水 =====

    /** 本租户交易流水，最新在前 */
    public List<BillingTransactionVO> listTransactions() {
        return this.transactionMapper.selectList(Wrappers.<BillingTransaction>lambdaQuery()
                        .orderByDesc(BillingTransaction::getId)).stream()
                .map(this::toTransactionVO).toList();
    }

    // ===== 发票 =====

    /**
     * 按账期开票。
     * <p>
     * 金额与 token 数取自该账期的 <b>deduction 流水汇总</b>，而不是重新按价格表计算——
     * 价格规则可以中途调整，按当时流水汇总才能与用户实际被扣的金额一致。
     * <p>
     * 同一账期重复开票直接拒绝：两张发票对账时无法判断以哪张为准。
     */
    public BillingInvoiceVO generateInvoice(String period) {
        if (!StringUtils.hasText(period)) {
            throw new BusinessException("账期不能为空");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(period.trim());
        }
        catch (RuntimeException e) {
            throw new BusinessException("账期格式应为 yyyy-MM：" + period);
        }
        BillingInvoice existing = this.invoiceMapper.selectOne(Wrappers.<BillingInvoice>lambdaQuery()
                .eq(BillingInvoice::getPeriod, month.toString()).last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException("该账期已开票：" + month);
        }

        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        List<BillingTransaction> deductions = this.transactionMapper.selectList(
                Wrappers.<BillingTransaction>lambdaQuery()
                        .eq(BillingTransaction::getType, TYPE_DEDUCTION)
                        .ge(BillingTransaction::getCreatedAt, from)
                        .lt(BillingTransaction::getCreatedAt, to));

        long totalTokens = 0L;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (BillingTransaction tx : deductions) {
            totalTokens += tx.getTokens() == null ? 0L : tx.getTokens();
            // 扣费流水的 amount 是负数，开票金额取绝对值
            totalAmount = totalAmount.add(value(tx.getAmount()).abs());
        }

        BillingInvoice invoice = new BillingInvoice();
        invoice.setPeriod(month.toString());
        invoice.setTotalTokens(totalTokens);
        invoice.setTotalAmount(totalAmount);
        this.invoiceMapper.insert(invoice);
        return toInvoiceVO(invoice);
    }

    /** 本租户发票，最新账期在前 */
    public List<BillingInvoiceVO> listInvoices() {
        return this.invoiceMapper.selectList(Wrappers.<BillingInvoice>lambdaQuery()
                        .orderByDesc(BillingInvoice::getPeriod)).stream()
                .map(this::toInvoiceVO).toList();
    }

    // ===== 校验与转换 =====

    private BillingPlan requirePlan(Long id) {
        BillingPlan plan = this.planMapper.selectById(id);
        if (plan == null) {
            throw new BusinessException("套餐不存在：" + id);
        }
        return plan;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private long requireNonNegative(Long value, String message) {
        if (value == null || value < 0) {
            throw new BusinessException(message);
        }
        return value;
    }

    private BigDecimal requireNonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw new BusinessException(message);
        }
        return value;
    }

    private BillingPlanVO toPlanVO(BillingPlan plan) {
        return new BillingPlanVO(plan.getId(), plan.getPlanCode(), plan.getPlanName(),
                plan.getMonthlyQuota(), plan.getPrice());
    }

    private BillingPriceRuleVO toPriceRuleVO(BillingPriceRule rule) {
        return new BillingPriceRuleVO(rule.getId(), rule.getModelName(),
                rule.getInputPrice(), rule.getOutputPrice(), rule.getEffectiveDate());
    }

    /** 供后续切片（扣费）复用：取某模型在指定日期生效的单价 */
    public BillingPriceRule effectiveRule(String modelName, LocalDate date) {
        return this.priceRuleMapper.selectOne(Wrappers.<BillingPriceRule>lambdaQuery()
                .eq(BillingPriceRule::getModelName, modelName)
                .le(BillingPriceRule::getEffectiveDate, date)
                .orderByDesc(BillingPriceRule::getEffectiveDate)
                .last("LIMIT 1"));
    }

    /** 本租户账户（唯一索引保证最多一条） */
    private BillingAccount currentAccount() {
        return this.accountMapper.selectOne(
                Wrappers.<BillingAccount>lambdaQuery().last("LIMIT 1"));
    }

    private BillingAccount requireAccount(Long id) {
        BillingAccount account = this.accountMapper.selectById(id);
        if (account == null) {
            throw new BusinessException("计费账户不存在：" + id);
        }
        return account;
    }

    private BillingPlan requirePlanByCode(String planCode) {
        BillingPlan plan = this.planMapper.selectOne(Wrappers.<BillingPlan>lambdaQuery()
                .eq(BillingPlan::getPlanCode, planCode).last("LIMIT 1"));
        if (plan == null) {
            // 与「被引用的套餐不可删」互补：写侧也保证账户不会指向不存在的套餐
            throw new BusinessException("套餐不存在：" + planCode);
        }
        return plan;
    }

    /** 状态为空按 active；非法值当场报错，避免拼错的状态悄悄落库 */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_ACTIVE;
        }
        String normalized = status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_STATUS.contains(normalized)) {
            throw new BusinessException("状态只能是 active / suspended / arrears，实际为：" + status);
        }
        return normalized;
    }

    private void insertTransaction(String type, BigDecimal amount, BigDecimal balanceAfter,
                                   Long tokens, String remark) {
        BillingTransaction transaction = new BillingTransaction();
        // 单号用 UUID：它有 32 位十六进制，撞号概率可忽略，且不需要额外查库生成序号
        transaction.setTransactionNo(UUID.randomUUID().toString().replace("-", ""));
        transaction.setType(type);
        transaction.setAmount(amount);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setTokens(tokens);
        transaction.setRemark(remark);
        this.transactionMapper.insert(transaction);
    }

    private BillingTransactionVO toTransactionVO(BillingTransaction transaction) {
        return new BillingTransactionVO(transaction.getId(), transaction.getTransactionNo(),
                transaction.getType(), transaction.getAmount(), transaction.getBalanceAfter(),
                transaction.getTokens(), transaction.getRemark(), transaction.getCreatedAt());
    }

    private BillingInvoiceVO toInvoiceVO(BillingInvoice invoice) {
        return new BillingInvoiceVO(invoice.getId(), invoice.getPeriod(),
                invoice.getTotalTokens(), invoice.getTotalAmount(), invoice.getCreatedAt());
    }

    private static BigDecimal value(BigDecimal number) {
        return number == null ? BigDecimal.ZERO : number;
    }

}
