package com.duduke.erp.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 仓库模块 Tool 的只读查询。
 * <p>
 * 与 {@link SalesToolMapper} 同样<b>不写 ent_code、不加 {@code @InterceptorIgnore}</b>，
 * 租户条件交给插件。
 * <p>
 * 这里 JOIN {@code product} 是<b>被表结构逼出来的</b>：本项目的 {@code inventory}
 * 是规范化设计，只存 {@code product_id} 与冗余的 {@code product_name}，
 * <b>没有 {@code product_code}</b>（参考实现把编码冗余在库存表里，所以它不用 JOIN）。
 * 而产品编码是 ERP 里的业务主键，模型必须拿到才有用，所以只能关联取。
 */
public interface WarehouseToolMapper {

    /** 按产品编码或名称查各仓库库存。返回行数上限 20。 */
    @Select("""
            SELECT p.product_code, i.product_name, i.warehouse, i.location,
                   i.batch_no, i.quantity, i.safety_stock
            FROM inventory i
            JOIN product p
              ON p.ent_code = i.ent_code
             AND p.id = i.product_id
            WHERE p.product_code = #{product}
               OR i.product_name LIKE #{pattern}
            ORDER BY i.warehouse, i.product_name
            LIMIT 20
            """)
    List<Map<String, Object>> selectInventoryByProduct(@Param("product") String product,
                                                       @Param("pattern") String pattern);

    /** 按仓库查库存明细。返回行数上限 50。 */
    @Select("""
            SELECT p.product_code, i.product_name, i.warehouse, i.location,
                   i.batch_no, i.quantity, i.safety_stock
            FROM inventory i
            JOIN product p
              ON p.ent_code = i.ent_code
             AND p.id = i.product_id
            WHERE i.warehouse = #{warehouse}
               OR i.warehouse LIKE #{pattern}
            ORDER BY i.product_name
            LIMIT 50
            """)
    List<Map<String, Object>> selectInventoryByWarehouse(@Param("warehouse") String warehouse,
                                                         @Param("pattern") String pattern);

    /** 按产品名称模糊查出入库流水。 */
    @Select("""
            SELECT movement_no, movement_type, product_name, warehouse, quantity,
                   TO_CHAR(movement_date, 'YYYY-MM-DD') AS movement_date, related_no
            FROM stock_movement
            WHERE product_name LIKE #{pattern}
            ORDER BY movement_date DESC
            LIMIT 20
            """)
    List<Map<String, Object>> selectMovementsByProduct(@Param("pattern") String pattern);

    /** 按时间范围查出入库流水。 */
    @Select("""
            SELECT movement_no, movement_type, product_name, warehouse, quantity,
                   TO_CHAR(movement_date, 'YYYY-MM-DD') AS movement_date, related_no
            FROM stock_movement
            WHERE movement_date BETWEEN #{from} AND #{to}
            ORDER BY movement_date DESC
            LIMIT 50
            """)
    List<Map<String, Object>> selectMovementsByDateRange(@Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);

    /** 库存预警：可用数量低于安全库存的产品，缺口大的在前。 */
    @Select("""
            SELECT p.product_code, i.product_name, i.warehouse,
                   i.quantity, i.safety_stock,
                   (i.safety_stock - i.quantity) AS shortage
            FROM inventory i
            JOIN product p
              ON p.ent_code = i.ent_code
             AND p.id = i.product_id
            WHERE i.quantity < i.safety_stock
            ORDER BY shortage DESC
            LIMIT 30
            """)
    List<Map<String, Object>> selectLowStockAlerts();

}
