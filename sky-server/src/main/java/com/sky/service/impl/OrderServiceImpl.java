package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.OrderCancelVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Value("${sky.payment.mock-enabled:false}")
    private boolean mockPaymentEnabled;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {

        //1. 处理各种用户异常（地址簿为空，购物车为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            //抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //查询用户购物车数据
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            //抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //2. 向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        //DTO里面没有的数据要手动设置
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);
        orders.setAddress(addressBook.getProvinceName() + addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail());

        orderMapper.insert(orders);

        //3. 向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();//订单明细
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());//设置当前订单明细关联的订单id
            orderDetailList.add(orderDetail);
        }

        orderDetailMapper.insertBatch(orderDetailList);

        //4. 清空当前购物车数据
        shoppingCartMapper.deleteByUserId(userId);

        //5. 封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId = currentUserId();
        if (ordersPaymentDTO == null || !StringUtils.hasText(ordersPaymentDTO.getOrderNumber())) {
            throw new OrderBusinessException("订单号不能为空");
        }
        if (!Integer.valueOf(1).equals(ordersPaymentDTO.getPayMethod())) {
            throw new OrderBusinessException("仅支持微信支付");
        }

        // 行锁一直持有到事务提交；订单号和归属均由服务端校验。
        Orders orders = orderMapper.getByNumberForUpdate(ordersPaymentDTO.getOrderNumber());
        if (orders == null || !userId.equals(orders.getUserId())) {
            throw new OrderBusinessException("订单不存在或无权操作");
        }
        if (isSuccessfullyPaid(orders)) {
            // 网络重试、重复点击均返回成功，不重写结账时间，也不倒退配送状态。
            return paymentResult(orders);
        }
        requirePendingPayment(orders);
        if (orders.getOrderTime() == null ||
                !orders.getOrderTime().plusMinutes(15).isAfter(LocalDateTime.now())) {
            throw new OrderBusinessException("订单已超时，请取消后重新下单");
        }
        if (orders.getAmount() == null || orders.getAmount().signum() <= 0) {
            throw new OrderBusinessException("订单金额异常");
        }

        if (mockPaymentEnabled) {
            // 与真实支付成功共用状态更新逻辑；事务提交后才会向小程序返回成功。
            completePayment(orders);
            return paymentResult(orders);
        }

        User user = userMapper.getById(userId);
        if (user == null || !StringUtils.hasText(user.getOpenid())) {
            throw new OrderBusinessException("微信用户信息异常，请重新登录");
        }
        JSONObject jsonObject = weChatPayUtil.pay(
                orders.getNumber(), orders.getAmount(), "苍穹外卖订单", user.getOpenid());
        if (jsonObject == null || jsonObject.getString("code") != null ||
                !StringUtils.hasText(jsonObject.getString("package"))) {
            throw new OrderBusinessException("微信预支付失败，请稍后重试");
        }
        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));
        vo.setMockPayment(false);
        vo.setPaid(false);
        vo.setOrderId(orders.getId());
        vo.setOrderNumber(orders.getNumber());
        vo.setOrderStatus(orders.getStatus());
        vo.setPayStatus(orders.getPayStatus());
        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(String outTradeNo) {
        if (!StringUtils.hasText(outTradeNo)) {
            throw new OrderBusinessException("订单号不能为空");
        }
        Orders orders = orderMapper.getByNumberForUpdate(outTradeNo);
        if (orders == null) {
            throw new OrderBusinessException("订单不存在");
        }
        if (!isSuccessfullyPaid(orders)) {
            completePayment(orders);
        }
    }

    private void completePayment(Orders orders) {
        requirePendingPayment(orders);
        LocalDateTime checkoutTime = LocalDateTime.now();
        Orders update = Orders.builder()
                .id(orders.getId())
                .userId(orders.getUserId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .payMethod(1)
                .checkoutTime(checkoutTime)
                .build();
        // 条件更新是最后一道保护，取消、退款等状态不允许被覆盖为支付成功。
        if (orderMapper.markPaid(update) != 1) {
            throw new OrderBusinessException("订单状态已变化，请刷新后重试");
        }
        orders.setStatus(update.getStatus());
        orders.setPayStatus(update.getPayStatus());
        orders.setPayMethod(update.getPayMethod());
        orders.setCheckoutTime(checkoutTime);
    }

    private void requirePendingPayment(Orders orders) {
        if (!Orders.PENDING_PAYMENT.equals(orders.getStatus()) ||
                !Orders.UN_PAID.equals(orders.getPayStatus())) {
            throw new OrderBusinessException("当前订单状态不允许支付");
        }
    }

    private boolean isSuccessfullyPaid(Orders orders) {
        Integer status = orders.getStatus();
        return Orders.PAID.equals(orders.getPayStatus()) && status != null &&
                status >= Orders.TO_BE_CONFIRMED && status <= Orders.COMPLETED;
    }

    private OrderPaymentVO paymentResult(Orders orders) {
        return OrderPaymentVO.builder()
                .mockPayment(mockPaymentEnabled)
                .paid(true)
                .orderId(orders.getId())
                .orderNumber(orders.getNumber())
                .orderStatus(orders.getStatus())
                .payStatus(orders.getPayStatus())
                .build();
    }

    private Long currentUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new UserNotLoginException("请先登录");
        }
        return userId;
    }

    private Orders currentUserOrder(Long id) {
        Orders orders = orderMapper.getByIdAndUserId(id, currentUserId());
        if (orders == null) {
            throw new OrderBusinessException("订单不存在或无权操作");
        }
        return orders;
    }

    /**
     * 查询当前用户订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO getOrderDetail(Long id) {
        Orders orders = currentUserOrder(id);
        OrderVO vo = new OrderVO();
        BeanUtils.copyProperties(orders, vo);
        vo.setOrderDetailList(orderDetailMapper.getByOrderId(id));
        return vo;
    }

    /**
     * 取消订单
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelPendingOrder(Long id) {
        // 兼容已有的超时取消调用，禁止在支付完成后自动退款。
        cancelOrder(id, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCancelVO cancelOrder(Long id, boolean unpaidOnly) {
        Orders orders = orderMapper.getByIdAndUserIdForUpdate(id, currentUserId());
        if (orders == null) {
            throw new OrderBusinessException("订单不存在或无权操作");
        }
        if (Orders.CANCELLED.equals(orders.getStatus()) &&
                (Orders.UN_PAID.equals(orders.getPayStatus()) || Orders.REFUND.equals(orders.getPayStatus()))) {
            // 重试使用首次取消结果，不重复退款，也不覆盖首次取消时间。
            return cancelResult(orders);
        }
        if (Orders.CONFIRMED.equals(orders.getStatus()) || Orders.DELIVERY_IN_PROGRESS.equals(orders.getStatus())) {
            throw new OrderBusinessException("商家已接单或订单正在派送，取消订单请电话联系商家");
        }
        boolean unpaid = Orders.PENDING_PAYMENT.equals(orders.getStatus()) && Orders.UN_PAID.equals(orders.getPayStatus());
        boolean paid = Orders.TO_BE_CONFIRMED.equals(orders.getStatus()) && Orders.PAID.equals(orders.getPayStatus());
        if (!unpaid && !paid) {
            throw new OrderBusinessException("当前订单状态不允许取消");
        }
        if (unpaidOnly && !unpaid) {
            throw new OrderBusinessException("订单已支付，自动取消已停止，请刷新订单");
        }
        if (paid && !mockPaymentEnabled) {
            throw new OrderBusinessException("当前未启用模拟退款，请联系商家处理");
        }
        if (paid && (orders.getAmount() == null || orders.getAmount().signum() <= 0 ||
                !Integer.valueOf(1).equals(orders.getPayMethod()))) {
            throw new OrderBusinessException("订单支付信息异常，请联系商家处理");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean expired = unpaid && orders.getOrderTime() != null && !orders.getOrderTime().plusMinutes(15).isAfter(now);
        Orders update = Orders.builder()
                .id(id)
                .userId(orders.getUserId())
                .status(Orders.CANCELLED)
                .payStatus(paid ? Orders.REFUND : Orders.UN_PAID)
                .cancelTime(now)
                .cancelReason(expired ? "支付超时，订单自动取消" : paid ? "用户取消待接单订单，模拟退款成功" : "用户取消待付款订单")
                .build();
        // 取消与模拟退款一次落库；金额、原结账时间和订单明细均保持不变。
        if (orderMapper.cancelOrder(update, orders.getStatus(), orders.getPayStatus()) != 1) {
            throw new OrderBusinessException("订单状态已变化，请刷新后重试");
        }
        // 返回数据库实际保存的时间精度，使首次响应与重复请求完全一致。
        return cancelResult(orderMapper.getByIdAndUserId(id, orders.getUserId()));
    }

    private OrderCancelVO cancelResult(Orders orders) {
        boolean refunded = Orders.REFUND.equals(orders.getPayStatus());
        return OrderCancelVO.builder()
                .id(orders.getId())
                .status(orders.getStatus())
                .payStatus(orders.getPayStatus())
                .cancelReason(orders.getCancelReason())
                .cancelTime(orders.getCancelTime())
                .mockRefund(refunded && mockPaymentEnabled)
                .refundAmount(refunded ? orders.getAmount() : BigDecimal.ZERO)
                .build();
    }

    /**
     * 历史订单查询
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    @Override
    public PageResult pageQuery(int page, int pageSize, Integer status) {
        //设置分页
        PageHelper.startPage(page, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        // 获取当前用户id
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        //设置分页查询
        Page<Orders> pageQuery = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();
        if (pageQuery != null && pageQuery.getTotal() >0) {
            for (Orders orders : pageQuery) {

                // 获取订单id
                Long orderId = orders.getId();

                //查询订单明细
                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderId);

                //将订单明细添加到 VO中
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetailList);

                list.add(orderVO);
            }

        }


        //返回结果
        return new PageResult(pageQuery.getTotal(), list);
    }

}
