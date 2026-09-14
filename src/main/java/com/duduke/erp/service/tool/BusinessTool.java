package com.duduke.erp.service.tool;

/**
 * 业务 Tool 的标记接口。
 * <p>
 * 存在的唯一目的是让注册表能一次性拿到全部业务 Tool：
 * Spring 会把所有实现类注入成 {@code List<BusinessTool>}，
 * 新增一个模块 Tool 只要实现本接口、其余代码零改动。
 * <p>
 * 之所以不用「扫描 {@code @Tool} 注解」或「按包扫描」：
 * 标记接口是<b>显式</b>的——一个类实现了它，就是"我把能力交给模型"的声明；
 * 注解扫描则会把任何带 {@code @Tool} 方法的类都卷进来，
 * 某天有人在工具类里顺手写个 {@code @Tool} 就悄悄多出一个对模型可见的函数。
 */
public interface BusinessTool {
}
