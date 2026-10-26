package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentVO implements Serializable {

    private String nonceStr; //随机字符串
    private String paySign; //签名
    private String timeStamp; //时间戳
    private String signType; //签名算法
    private String packageStr; //统一下单接口返回的 prepay_id 参数值

    private Boolean mockPayment; //是否为模拟支付，由服务端配置决定
    private Boolean paid; //是否已经完成支付并更新数据库
    private Long orderId;
    private String orderNumber;
    private Integer orderStatus;
    private Integer payStatus;

}
