package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.exception.OrderBusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 使用百度地理编码和驾车路线的实际距离校验配送范围。 */
@Service
@Slf4j
public class DeliveryRangeService {
    private static final String GEOCODING_URL = "https://api.map.baidu.com/geocoding/v3/";
    private static final String DRIVING_URL = "https://api.map.baidu.com/directionlite/v1/driving";
    private static final BigDecimal MAX_DISTANCE_METERS = BigDecimal.valueOf(10_000);

    @Value("${sky.shop.address:}")
    private String shopAddress;
    @Value("${sky.baidu.ak:}")
    private String ak;

    private final RestTemplate restTemplate;

    public DeliveryRangeService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        this.restTemplate = new RestTemplate(factory);
    }

    // 隔离测试绑定 MockRestServiceServer，不发送真实地图请求。
    DeliveryRangeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void check(String address) {
        if (!StringUtils.hasText(shopAddress)) {
            throw new OrderBusinessException("商家暂未配置配送起点，请联系商家");
        }
        if (!StringUtils.hasText(ak)) {
            throw new OrderBusinessException("地图服务未配置，暂时无法校验配送范围");
        }
        if (!StringUtils.hasText(address)) {
            throw new OrderBusinessException("收货地址不能为空");
        }

        String origin = geocode(shopAddress, "店铺地址解析");
        String destination = geocode(address, "收货地址解析");
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(DRIVING_URL)
                .queryParam("origin", origin).queryParam("destination", destination)
                .queryParam("coord_type", "bd09ll").queryParam("steps_info", "0")
                .queryParam("ak", ak.trim());
        JSONObject response = request(builder, "配送路线规划");
        JSONObject result = object(response.get("result"), "配送路线规划");
        Object routesValue = result.get("routes");
        if (!(routesValue instanceof JSONArray) || ((JSONArray) routesValue).isEmpty()) {
            throw new OrderBusinessException("未找到可配送路线，请更换收货地址");
        }
        JSONObject route = object(((JSONArray) routesValue).get(0), "配送路线规划");
        BigDecimal distance = number(route.get("distance"), "配送路线规划");
        if (distance.signum() < 0) {
            throw invalidResponse("配送路线规划");
        }
        if (distance.compareTo(MAX_DISTANCE_METERS) > 0) {
            throw new OrderBusinessException("超出配送范围，商家仅支持10公里内配送");
        }
    }

    private String geocode(String address, String stage) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(GEOCODING_URL)
                .queryParam("address", address.trim()).queryParam("output", "json")
                .queryParam("ret_coordtype", "bd09ll").queryParam("ak", ak.trim());
        JSONObject response = request(builder, stage);
        JSONObject result = object(response.get("result"), stage);
        JSONObject location = object(result.get("location"), stage);
        BigDecimal lat = number(location.get("lat"), stage);
        BigDecimal lng = number(location.get("lng"), stage);
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 ||
                lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw invalidResponse(stage);
        }
        // 路线接口要求纬度在前、经度在后，且小数位不超过6位。
        return lat.setScale(6, RoundingMode.HALF_UP).toPlainString() + "," +
                lng.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private JSONObject request(UriComponentsBuilder builder, String stage) {
        URI uri = builder.build().encode(StandardCharsets.UTF_8).toUri();
        String body;
        try {
            body = restTemplate.getForObject(uri, String.class);
        } catch (RestClientException ex) {
            // 不记录含 AK 的请求地址、异常堆栈或原始响应。
            log.warn("百度地图{}请求失败，异常类型={}", stage, ex.getClass().getSimpleName());
            throw new OrderBusinessException("地图服务暂时不可用，请稍后重试");
        }
        JSONObject response;
        Integer status;
        try {
            response = JSON.parseObject(body);
            status = response == null ? null : response.getInteger("status");
        } catch (RuntimeException ex) {
            throw invalidResponse(stage);
        }
        if (status == null) {
            throw invalidResponse(stage);
        }
        if (status != 0) {
            log.warn("百度地图{}失败，status={}", stage, status);
            throw new OrderBusinessException(providerError(stage, status));
        }
        return response;
    }

    private String providerError(String stage, int status) {
        switch (status) {
            case 210:
                return "地图服务IP校验失败（210），请联系商家检查百度地图IP白名单";
            case 211:
                return "地图服务签名校验失败（211），请联系商家检查百度地图AK校验方式";
            case 240:
                return "地图服务权限未开启（240），请联系商家开通地理编码和路线规划服务";
            case 4: case 301: case 302: case 401: case 402:
                return "地图服务调用额度或并发受限（" + status + "），请稍后重试或联系商家";
            case 3: case 5: case 101: case 102: case 200: case 201: case 202: case 203: case 220: case 230:
                return "地图服务密钥或权限校验失败（" + status + "），请联系商家检查百度地图配置";
            default:
                return stage + "失败（百度状态码：" + status + "），请检查地址或稍后重试";
        }
    }

    private JSONObject object(Object value, String stage) {
        if (!(value instanceof JSONObject)) {
            throw invalidResponse(stage);
        }
        return (JSONObject) value;
    }

    private BigDecimal number(Object value, String stage) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw invalidResponse(stage);
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            throw invalidResponse(stage);
        }
    }

    private OrderBusinessException invalidResponse(String stage) {
        return new OrderBusinessException(stage + "返回数据异常，请稍后重试");
    }
}
