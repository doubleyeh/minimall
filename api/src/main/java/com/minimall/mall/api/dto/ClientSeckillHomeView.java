package com.minimall.mall.api.dto;

import java.util.List;

/**
 * 小程序秒杀首页:按"进行中 / 即将开始"分好组。
 *
 * <p>分组放在服务端做,是因为"现在算不算进行中"必须用服务端时间判断 ——
 * 端上时钟不准会让用户看到"进行中"却抢不了。
 */
public record ClientSeckillHomeView(
        List<ClientSeckillItemView> running,
        List<ClientSeckillItemView> upcoming) {
}
