package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    /**
     * 新增订单
     * @param orders
     */
    public void insert(Orders orders);

    /**
     * 根据订单号查询订单
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /** 支付时加行锁，避免重复支付或并发回调覆盖订单状态。 */
    @Select("select * from orders where number = #{orderNumber} for update")
    Orders getByNumberForUpdate(String orderNumber);

    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 仅将当前用户的待付款、未支付订单改为已支付。 */
    int markPaid(Orders orders);

    /** 仅取消当前用户的待付款、未支付订单，不涉及退款。 */
    int cancelPendingPayment(Orders orders);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);

    /**
     * 订单分页查询
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);
}
