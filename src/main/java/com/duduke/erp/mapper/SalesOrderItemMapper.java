package com.duduke.erp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.SalesOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 销售订单明细数据访问。
 */
public interface SalesOrderItemMapper extends BaseMapper<SalesOrderItem> {

    /**
     * 按订单号取全部明细行。
     *
     * @param orderNo 订单号
     * @return 明细列表，无明细时返回空列表
     */
    @Select("SELECT * FROM sales_order_item WHERE order_no = #{orderNo}")
    List<SalesOrderItem> selectByOrderNo(@Param("orderNo") String orderNo);

}
