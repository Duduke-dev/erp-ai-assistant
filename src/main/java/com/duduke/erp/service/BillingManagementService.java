package com.duduke.erp.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.BillingPlanSaveDTO;
import com.duduke.erp.entity.dto.BillingPriceRuleSaveDTO;
import com.duduke.erp.entity.po.BillingPlan;
import com.duduke.erp.entity.po.BillingPriceRule;
import com.duduke.erp.entity.vo.BillingPlanVO;
import com.duduke.erp.entity.vo.BillingPriceRuleVO;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.mapper.BillingPlanMapper;
import com.duduke.erp.mapper.BillingPriceRuleMapper;

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

}
