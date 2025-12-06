# 优惠券异步领取业务实现文档

## 一、功能概述

本次完善了优惠券异步领取业务，通过RabbitMQ消息队列实现了异步处理，提升系统性能和用户体验。

## 二、核心功能

### 1. 异步处理流程

```
用户请求领取优惠券
  ↓
基于Redis缓存校验(优惠券信息、时间、用户限领)
  ↓
Redis预扣库存(快速失败)
  ↓
发送MQ消息
  ↓
立即返回用户(提升响应速度)
  ↓
MQ消费者异步处理
  ↓
实际领取业务(分布式锁+事务+数据库最终校验)
  ↓
更新用户领取缓存
```

### 2. Redis缓存优化

#### 2.1 优惠券信息缓存
- **缓存内容**: issueBeginTime、issueEndTime、totalNum、userLimit
- **缓存时机**: 优惠券开始发放时
- **缓存结构**: Hash，key为 `prs:coupon:{couponId}`

#### 2.2 用户领取数量缓存
- **缓存内容**: 用户已领取某优惠券的数量
- **缓存时机**: 用户领取成功后自增
- **缓存结构**: String，key为 `prs:coupon:{couponId}:prs:usr:coupon:{userId}`

#### 2.3 优惠券库存缓存
- **目的**: 快速失败，减少无效的MQ消息
- **实现**: 在发送MQ前先预扣Redis库存
- **回滚**: 如果发送MQ失败或业务处理失败，自动回滚Redis库存
- **缓存结构**: String，key为 `coupon:stock:{couponId}`

### 3. 分布式锁保证

- 使用Redisson分布式锁(@Lock注解)
- 锁粒度: 基于userId，同一用户的请求串行处理
- 避免: 同一用户重复领取同一优惠券

## 三、核心组件

### 1. MQ配置 (PromotionConfig.java)

```java
- 交换机: promotion.topic (Topic类型)
- 队列: promotion.coupon.receive.queue (持久化)
- RoutingKey: coupon.receive
```

### 2. 消息监听器 (CouponReceiveListener.java)

- 监听优惠券领取消息
- 调用异步领取业务逻辑
- 完善的异常处理和日志记录

### 3. 业务服务 (UserCouponServiceImpl.java)

#### getUserCoupon方法 (发送MQ)
- 从Redis获取优惠券缓存信息
- 校验发放时间（基于Redis）
- 校验用户限领数量（基于Redis）
- Redis预扣库存
- 发送MQ消息
- 异常时回滚库存

#### asyncReceiveCoupon方法 (消费MQ)
- 查询优惠券信息（仅查询，不校验）
- 调用核心领取逻辑（数据库最终校验）
- 领取成功后更新用户已领取数量缓存
- 异常时回滚Redis库存

#### checkAndCreateUserCoupon方法 (核心逻辑)
- 分布式锁保护(@Lock注解)
- 事务保证(@Transactional)
- 数据库库存扣减
- 用户券记录创建

### 4. 库存初始化 (CouponServiceImpl.java)

- 在优惠券开始发放时初始化Redis库存
- 在优惠券暂停后恢复时重新初始化Redis库存

## 四、关键优化点

### 1. 性能优化

- **异步处理**: 用户请求快速响应，不需要等待数据库操作
- **Redis缓存校验**: 所有校验都在Redis中完成，不查询数据库
- **Redis预扣库存**: 利用Redis高性能特性快速判断库存
- **减少DB压力**: 高并发场景下大幅减少数据库访问，只在最终领取时才访问DB

### 2. 可靠性保证

- **数据库最终校验**: checkAndCreateUserCoupon中的数据库操作保证数据一致性
- **库存回滚**: 异常场景自动回滚Redis库存
- **分布式锁**: 基于userId，防止同一用户并发领取导致超领
- **事务保证**: 数据一致性保证
- **用户限领校验**: 查询数据库统计已领取数量，避免超限

**⚠️ 幂等性增强建议**:
- 当前通过分布式锁+用户限领校验+不抛异常避免重试来防止重复领取
- 为了更强的幂等性保证，建议在`user_coupon`表添加`(user_id, coupon_id, status)`的唯一索引
- SQL示例：`ALTER TABLE user_coupon ADD UNIQUE INDEX uk_user_coupon (user_id, coupon_id);`

### 3. 监控与日志

- 关键节点添加日志记录
- 异常信息详细记录
- 便于问题排查和监控

## 五、使用说明

### 1. 前置条件

- RabbitMQ服务正常运行
- Redis服务正常运行
- 已配置RabbitMQ和Redis连接信息

### 2. 配置项检查

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
  redis:
    host: localhost
    port: 6379
```

### 3. API调用

```
POST /coupons/{couponId}/receive
```

## 六、注意事项

### 1. Redis缓存同步

- **优惠券信息缓存**: 优惠券发放时自动初始化
- **优惠券库存缓存**: 优惠券发放时会自动初始化，暂停后恢复时会重新同步
- **用户领取缓存**: 用户领取成功后自动更新
- 确保Redis和数据库数据的最终一致性
- 如果缓存丢失，优惠券需要重新发放才会重建缓存

### 2. 消息可靠性

- 队列设置为持久化
- 建议配置消息确认机制
- 可配置死信队列处理失败消息

### 3. 并发控制

- 分布式锁基于用户维度
- 同一用户的请求会串行处理
- 不同用户可并发处理

### 4. 监控告警

- 监控MQ消息积压情况
- 监控Redis库存与DB库存的差异
- 监控优惠券领取成功率

## 七、扩展建议

### 1. 死信队列

建议配置死信队列处理失败消息:
```java
@Bean
public Queue couponReceiveDeadLetterQueue() {
    return QueueBuilder
        .durable("promotion.coupon.receive.dlq")
        .build();
}
```

### 2. 重试机制

可配置消息重试策略:
```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000
```

### 3. 限流保护

建议在Controller层添加限流保护:
```java
@RateLimiter(value = "coupon-receive", limit = 10, timeout = 1)
public void receiveCoupon(Long couponId) {
    // ...
}
```

### 4. 数据库唯一索引实现真正的幂等性

**问题**: 当前实现依赖分布式锁和查询校验，在极端情况下（如MQ重复投递）可能出现重复领取。

**解决方案**: 添加数据库唯一索引

```sql
-- 添加唯一索引，防止同一用户重复领取同一优惠券
ALTER TABLE user_coupon
ADD UNIQUE INDEX uk_user_coupon (user_id, coupon_id);
```

**修改代码处理唯一索引冲突**:

```java
@Override
public void asyncReceiveCoupon(UserCouponDTO dto) {
    Coupon coupon = couponMapper.selectById(dto.getCouponId());
    if (coupon == null) {
        log.error("优惠券不存在: couponId={}", dto.getCouponId());
        rollbackRedisStock(dto.getCouponId());
        return;
    }

    try {
        IUserCouponService proxy = (IUserCouponService) AopContext.currentProxy();
        proxy.checkAndCreateUserCoupon(coupon, dto.getUserId(), null);

        // 成功后更新用户领取缓存
        String userCouponKey = RedisConstants.COUPON_CACHE_KEY_PREFIX + dto.getCouponId()
                + RedisConstants.USER_COUPON_CACHE_KEY_SUFFIX + dto.getUserId();
        redisTemplate.opsForValue().increment(userCouponKey);

        log.info("优惠券领取成功: userId={}, couponId={}", dto.getUserId(), dto.getCouponId());
    } catch (DuplicateKeyException e) {
        // 捕获唯一索引冲突异常，说明已经领取过了，直接返回（幂等）
        log.warn("用户已领取过该优惠券（幂等）: userId={}, couponId={}", dto.getUserId(), dto.getCouponId());
        rollbackRedisStock(dto.getCouponId());
    } catch (Exception e) {
        rollbackRedisStock(dto.getCouponId());
        log.error("优惠券领取失败: userId={}, couponId={}, error={}",
                dto.getUserId(), dto.getCouponId(), e.getMessage());
    }
}
```

**优势**:
- 数据库层面强制保证幂等性
- 即使MQ消息重复投递也不会重复领取
- 更可靠的数据一致性保证

## 八、测试建议

### 1. 单元测试

- 测试Redis库存预扣
- 测试MQ消息发送
- 测试异步处理逻辑

### 2. 并发测试

- 使用JMeter等工具模拟高并发场景
- 验证分布式锁的有效性
- 验证库存扣减的准确性

### 3. 异常测试

- 模拟MQ服务异常
- 模拟Redis服务异常
- 验证回滚机制的正确性

## 九、问题排查

### 1. 优惠券领取失败

检查点:
- 优惠券是否在发放期内
- 优惠券库存是否充足
- 用户是否已达到领取上限
- MQ消息是否正常消费

### 2. Redis缓存不一致

可能表现:
- 库存缓存与数据库不一致
- 用户领取缓存与数据库不一致
- 优惠券信息缓存缺失

处理方案:
- 重启优惠券发放(会重新初始化缓存)
- 手动清理Redis缓存，让系统重建
- 检查异常日志，修复回滚逻辑
- 暂停优惠券后重新发放，触发缓存同步

### 3. MQ消息积压

可能原因:
- 消费速度慢于生产速度
- 业务处理异常导致重试
- 数据库性能瓶颈

解决方案:
- 增加消费者实例
- 优化业务处理逻辑
- 优化数据库查询

---

**文档版本**: v1.0
**更新日期**: 2025-11-25
**维护人员**: Development Team
