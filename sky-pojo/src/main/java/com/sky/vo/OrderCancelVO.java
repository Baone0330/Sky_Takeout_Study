package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 当前订单取消后的持久化结果；退款金额仅表示模拟退款。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelVO implements Serializable {
    private Long id;
    private Integer status;
    private Integer payStatus;
    private String cancelReason;
    private LocalDateTime cancelTime;
    private Boolean mockRefund;
    private BigDecimal refundAmount;
}
