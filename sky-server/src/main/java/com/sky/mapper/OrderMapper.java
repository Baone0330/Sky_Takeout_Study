package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {
    /**
     * 新增订单
     *
     * @param orders
     */
    public void insert(Orders orders);

    /**
     * 根据订单号查询订单
     *
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 支付时加行锁，避免重复支付或并发回调覆盖订单状态。
     */
    @Select("select * from orders where number = #{orderNumber} for update")
    Orders getByNumberForUpdate(String orderNumber);

    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select * from orders where id = #{id} and user_id = #{userId} for update")
    Orders getByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 仅将当前用户的待付款、未支付订单改为已支付。
     */
    int markPaid(Orders orders);

    /**
     * 按原始订单/支付状态执行取消，防止接单、支付或重复取消覆盖状态。
     */
    int cancelOrder(@Param("orders") Orders orders, @Param("expectedStatus") Integer expectedStatus,
                    @Param("expectedPayStatus") Integer expectedPayStatus);

    /**
     * 修改订单信息
     *
     * @param orders
     */
    void update(Orders orders);

    /**
     * 订单分页查询
     *
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 统计订单数量
     *
     * @param status
     * @return
     */
    @Select("select count(id) from orders where status = #{status} ")
    Integer countStatus(Integer status);

    /**
     * 根据id查询订单
     *
     * @param id
     * @return
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据状态统计订单数量
     *
     * @param status
     * @param orderTime
     * @return
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);

    /**
     * 根据动态条件统计营业额数据
     *
     * @param map
     * @return
     */
    Double sumByMap(Map<String, Object> map);

    /**
     * 根据动态条件统计订单数据
     *
     * @param map
     * @return
     */
    Integer countByMap(Map<String, Object> map);

    /**
     * 统计指定时间内的销量排名前十
     *
     * @param begin
     * @param end
     * @return
     */
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin, LocalDateTime end);
}
