# 复杂优惠券组合计算代码走读

## 1. 面试先给结论

这部分代码解决的是订单确认页的优惠券方案计算问题：用户可能同时持有多张不同规则、不同适用范围的优惠券，系统需要在不改变订单课程价格的前提下，找出可用的叠加顺序和优惠金额，并把每张券的优惠拆分到课程明细。

核心思路可以概括为：

1. 先查询用户未使用优惠券，使用订单总价做一次粗筛。
2. 再按优惠券适用课程范围做细筛，剔除订单中没有适用课程或未达到门槛的券。
3. 用回溯/全排列保留优惠券使用顺序，因为前一张券会改变后一张券的可用金额。
4. 将每个候选方案交给 `CompletableFuture`，使用独立线程池并行试算。
5. 按优惠金额优先、用券数量次优筛选最优方案，并返回课程级优惠明细。

题述的性能结果是：多组合场景接口响应时间由约 800ms 降到 100ms 以内。这个数字应在面试中绑定具体压测条件（候选券数量、课程数量、并发量、机器配置和统计口径）；当前仓库没有独立的基准测试脚本，本文不把该数字伪装成代码自动验证结果。

## 2. 业务问题与关键难点

### 2.1 为什么不能只计算单张券

订单中可能有多张券，例如：

- A：满 200 减 50；
- B：满 150 减 30；
- 订单金额：200。

如果先使用 A，剩余可计价金额为 150，B 仍可用，总优惠为 80；如果先使用 B，剩余金额为 170，A 不再满足满 200 条件，总优惠只有 30。也就是说，同一组券的使用顺序会影响后续券是否满足门槛。

因此，算法不能简单地对每张券独立计算，也不能只把券集合当成无序组合，而要试算有意义的使用顺序。

### 2.2 为什么范围筛选必须早于组合计算

一张券可能只适用于某个课程或某个三级分类。订单总价满足门槛，不代表该券限定范围内的课程金额也满足门槛。例如订单总价是 200 元，但券只适用于其中 80 元的课程，券的门槛是 100 元时，这张券不能进入候选方案。

范围筛选的结果不仅影响“这张券能不能用”，还影响每次叠加后的剩余金额和课程级优惠分摊。

## 3. 端到端调用链

订单确认页的调用链如下：

~~~text
GET /orders/prePlaceOrder
  -> tj-trade.OrderController#prePlaceOrder
  -> OrderServiceImpl#prePlaceOrder
     组装 OrderCourseDTO(id, cateId, price)
  -> PromotionClient#findDiscountSolution
  -> POST /user-coupons/available
  -> UserCouponController#findDiscountSolution
  -> DiscountServiceImpl#findDiscountSolution
     查询券、范围筛选、排列、并行试算、最优解筛选
  -> OrderConfirmVO.discounts
~~~

关键代码位置：

| 作用 | 代码位置 |
| --- | --- |
| 订单确认入口 | `tj-trade/src/main/java/com/tianji/trade/controller/OrderController.java:51-55` |
| 组装课程并调用促销服务 | `tj-trade/src/main/java/com/tianji/trade/service/impl/OrderServiceImpl.java:221-244` |
| Feign 接口 | `tj-api/src/main/java/com/tianji/api/client/promotion/PromotionClient.java:12-19` |
| 促销服务接口 | `tj-promotion/src/main/java/com/tianji/promotion/controller/UserCouponController.java:54-64` |
| 组合计算主流程 | `tj-promotion/src/main/java/com/tianji/promotion/service/impl/DiscountServiceImpl.java:40-93` |

用户真正下单时不会直接信任确认页返回的优惠金额。`OrderServiceImpl#placeOrder` 会根据前端提交的用户券 ID 和课程信息再次调用 `/user-coupons/discount`，由促销服务重新计算折扣，然后再核销优惠券。这一步保证了价格和券状态以服务端重算结果为准。

## 4. 输入、输出和金额语义

### 4.1 输入对象

`OrderCourseDTO` 只携带组合计算需要的最小信息：

- `id`：课程 ID；
- `cateId`：课程三级分类 ID；
- `price`：课程价格，单位为分。

`Coupon` 中与计算直接相关的字段：

- `discountType`：折扣类型；
- `specific`：是否限定范围；
- `discountValue`：满减金额、折扣率或每满减金额；
- `thresholdAmount`：使用门槛；
- `maxDiscountAmount`：最高优惠金额。

`UserCouponMapper.xml#queryMyCoupons` 查询的是用户券和券模板的组合，并将 `uc.id` 映射到 `Coupon.creater`。这里的 `creater` 是历史字段复用，业务含义实际是用户优惠券 ID，最终放入 `CouponDiscountDTO.ids`，不是优惠券创建人 ID。面试时要主动说明这个字段别名，避免误解。

### 4.2 输出对象

`CouponDiscountDTO` 的含义是一个候选方案：

- `ids`：该方案实际使用的用户券 ID 集合；
- `rules`：每张券的规则文案；
- `discountAmount`：订单总优惠金额，单位为分；
- `discountDetail`：`课程 ID -> 该课程优惠金额`。

一个订单可以返回多个方案，前端可以展示多个可选组合；方案列表按优惠金额降序排列。

## 5. `DiscountServiceImpl` 主流程走读

### 5.1 第一步：查询用户券并做订单级粗筛

入口是 `findDiscountSolution`：

~~~java
List<Coupon> coupons = userCouponMapper.queryMyCoupons(UserContext.getUser());
if (CollUtils.isEmpty(coupons)) {
    return CollUtils.emptyList();
}

int totalAmount = orderCourses.stream()
        .mapToInt(OrderCourseDTO::getPrice)
        .sum();

List<Coupon> availableCoupons = coupons.stream()
        .filter(c -> DiscountStrategy.getDiscount(c.getDiscountType())
                .canUse(totalAmount, c))
        .collect(Collectors.toList());
~~~

这一步的价值是先用订单总价淘汰明显不满足门槛的券，减少后面排列和线程池任务数量。它是粗筛，不能代替范围细筛，因为限定范围内的金额可能小于订单总价。

### 5.2 第二步：按券的适用范围细筛

`findAvailableCoupon` 最终建立：

~~~text
Coupon -> 该券在当前订单中可以作用的课程列表
~~~

当前磁盘代码的实际逻辑是：

1. 非限定券直接把全部订单课程作为可用课程；
2. 限定券查询 `coupon_scope`；
3. 取每条范围记录的 `bizId`；
4. 用 `bizId` 与订单课程的 `cateId` 匹配；
5. 计算限定课程的金额，再次调用折扣策略的 `canUse`；
6. 没有适用课程或未达到门槛的券不放入 Map。

~~~java
if (coupon.getSpecific()) {
    List<CouponScope> scopes = scopeService.lambdaQuery()
            .eq(CouponScope::getCouponId, coupon.getId())
            .list();
    Set<Long> scopeIds = scopes.stream()
            .map(CouponScope::getBizId)
            .collect(Collectors.toSet());
    availableCourses = courses.stream()
            .filter(c -> scopeIds.contains(c.getCateId()))
            .collect(Collectors.toList());
}

int totalAmount = availableCourses.stream()
        .mapToInt(OrderCourseDTO::getPrice)
        .sum();
if (DiscountStrategy.getDiscount(coupon.getDiscountType())
        .canUse(totalAmount, coupon)) {
    map.put(coupon, availableCourses);
}
~~~

细筛返回后，后续计算不再重复查询范围数据，候选方案只读取内存中的 `availableCouponMap`。

### 5.3 第三步：回溯生成候选使用顺序

主流程调用：

~~~java
availableCoupons = new ArrayList<>(availableCouponMap.keySet());
List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);

for (Coupon coupon : availableCoupons) {
    solutions.add(List.of(coupon));
}
~~~

`PermuteUtil` 使用“交换当前位置元素、递归、交换还原”的回溯方式：

~~~java
for (int i = first; i < n; i++) {
    Collections.swap(input, first, i);
    backtrack(n, input, res, first + 1);
    Collections.swap(input, first, i);
}
~~~

递归到末尾时复制当前列表，避免后续交换改写已经加入结果的方案。交换还原保证同一份输入列表可以继续探索其它分支。

为什么要保留顺序：`calculateSolutionDiscount` 会按 `solution` 中的顺序逐张计算，每张券都基于前面已经产生的课程折扣重新计算剩余金额，所以 `[A, B]` 和 `[B, A]` 可能是两个不同的结果。

### 5.4 第四步：并行试算每个方案

每个候选方案之间相互独立，因此主流程将方案计算提交到专用线程池：

~~~java
List<CouponDiscountDTO> list =
        Collections.synchronizedList(new ArrayList<>(solutions.size()));
CountDownLatch latch = new CountDownLatch(solutions.size());

for (List<Coupon> solution : solutions) {
    CompletableFuture
            .supplyAsync(
                    () -> calculateSolutionDiscount(
                            availableCouponMap, orderCourses, solution),
                    discountSolutionExecutor)
            .thenAccept(dto -> {
                list.add(dto);
                latch.countDown();
            });
}

latch.await(1, TimeUnit.SECONDS);
return findBestSolution(list);
~~~

这里的并行不是 MQ 异步：当前 HTTP 请求会等待闭锁，拿到试算结果后才返回。它只是把一个请求内部的 CPU 计算拆到多个线程执行。

`PromotionConfig#discountSolutionExecutor` 当前配置：

| 参数 | 当前值 | 作用 |
| --- | ---: | --- |
| corePoolSize | 12 | 核心工作线程数 |
| maxPoolSize | 12 | 固定并发上限，避免计算线程无限增长 |
| queueCapacity | 99999 | 等待执行的任务队列 |
| threadNamePrefix | `discount-solution-calculator-` | 便于日志和线程 dump 定位 |
| rejected policy | `AbortPolicy` | 线程池和队列耗尽时快速失败 |

每个方案内部会新建自己的 `detailMap`，只读共享 `availableCouponMap` 和课程列表，因此不同任务之间没有写同一份计算状态；结果列表使用同步包装，避免多个回调线程同时写入产生并发问题。

### 5.5 第五步：按顺序计算一组券的折扣

`calculateSolutionDiscount` 对一个方案逐张处理：

1. 为所有课程初始化优惠明细为 0；
2. 找到当前券适用的课程；
3. 用“课程价格 - 已有优惠”计算当前券看到的剩余金额；
4. 再次调用 `canUse`，因为前面的券可能让当前券失去门槛；
5. 调用策略计算当前券优惠；
6. 把本张券的优惠按课程价格比例分摊；
7. 累加方案总优惠、用户券 ID 和规则文案。

~~~java
int totalAmount = availableCourses.stream()
        .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId()))
        .sum();

Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
if (!discount.canUse(totalAmount, coupon)) {
    continue;
}

int discountAmount = discount.calculateDiscount(totalAmount, coupon);
calculateDiscountDetails(
        detailMap, availableCourses, totalAmount, discountAmount);
~~~

### 5.6 第六步：课程级优惠分摊

`calculateDiscountDetails` 使用比例分摊：

~~~text
课程优惠 = 本张券优惠金额 × 课程价格 / 当前适用课程总价
~~~

整数除法会产生余数，因此代码让最后一门课程承担剩余金额：

- 非最后一门课程：按比例向下取整；
- 最后一门课程：`总优惠 - 前面已经分配的优惠`；
- 最终保证课程明细之和等于本张券优惠金额，不因舍入丢分。

多张券叠加时，后一张券的分摊基于已经累计的 `detailMap`，所以同一课程可能有多张券产生的优惠，最终统一累加。

### 5.7 第七步：筛选最优解

`findBestSolution` 使用两个 Map 做二级筛选：

1. 以排序后的用户券 ID 串作为 key，处理同一组券不同排列顺序的结果，只保留优惠金额更大的排列；
2. 以优惠金额作为 key，在金额相同的方案中优先保留用券更少的方案；
3. 取两类最优结果的交集；
4. 按 `discountAmount` 降序返回。

可以把业务排序规则抽象成：

~~~text
(优惠金额 DESC, 使用券数量 ASC)
~~~

第一层“同券组取最大值”是必要的：如果 A、B 两张券有两个排列结果，最终只应该展示这组券中更有利的使用顺序。

## 6. 折扣策略模式走读

### 6.1 接口职责

`Discount` 把每种优惠规则统一成三个动作：

~~~java
boolean canUse(int totalAmount, Coupon coupon);
int calculateDiscount(int totalAmount, Coupon coupon);
String getRule(Coupon coupon);
~~~

组合编排代码只依赖接口，不需要知道每种券的公式。

### 6.2 当前策略

| 策略类 | 业务规则 | 计算特点 |
| --- | --- | --- |
| `NoThresholdDiscount` | 无门槛抵扣 | 直接抵扣 `discountValue`，当前实现要求剩余金额大于抵扣额 |
| `PriceDiscount` | 满 X 减 Y | 达到 `thresholdAmount` 后固定减 `discountValue` |
| `PerPriceDiscount` | 每满 X 减 Y | `floor(金额 / X) * Y`，再受最高优惠金额限制 |
| `RateDiscount` | 满 X 打折 | 按 `totalAmount * (100 - discountValue) / 100` 计算，并受最高优惠金额限制 |

策略注册在 `DiscountStrategy` 的 `EnumMap<DiscountType, Discount>` 中：

~~~java
strategies.put(DiscountType.NO_THRESHOLD, new NoThresholdDiscount());
strategies.put(DiscountType.PER_PRICE_DISCOUNT, new PerPriceDiscount());
strategies.put(DiscountType.RATE_DISCOUNT, new RateDiscount());
strategies.put(DiscountType.PRICE_DISCOUNT, new PriceDiscount());
~~~

新增优惠类型时，通常只需新增策略类并注册映射，组合计算主流程不需要继续堆叠 `if-else`。策略对象本身无可变状态，可以被多个并行任务安全复用。

## 7. 适用范围策略的现状与演进

仓库中已有 `Scope`、`NoScope`、`CategoryScope`、`CourseScope` 和 `ScopeType`，抽象接口包含：

~~~java
boolean canUse(OrderCourseDTO course);
ScopeType getType();
List<Long> getScopeIds();
~~~

其中：

- `NoScope` 对所有课程返回 true；
- `CategoryScope` 比较 `course.getCateId()`；
- `CourseScope` 比较 `course.getId()`。

但必须区分“类已经存在”和“主流程已经使用”：

- 当前 `DiscountServiceImpl#findAvailableCoupon` 没有调用 `ScopeType.buildScope` 或 `Scope#canUse`；
- 当前实现把 `coupon_scope.bizId` 统一当作分类 ID，与 `OrderCourseDTO.cateId` 比较；
- `CouponScope.type` 在该计算路径没有参与判断；
- `CouponServiceImpl` 保存范围时也只设置了 `bizId` 和 `couponId`。

因此，按当前磁盘代码，课程级范围策略虽然已经准备好，但并未真正接入优惠方案计算；“指定课程和指定分类都能计算”属于可演进设计，不能直接当成当前路径的已验证行为。

若要完整支持两类范围，推荐先把数据库范围记录转换成带类型的策略，再统一过滤：

~~~java
Scope scope = scopeType.buildScope(scopeIds);
List<OrderCourseDTO> availableCourses = courses.stream()
        .filter(scope::canUse)
        .collect(Collectors.toList());
~~~

实际接入时还需要保证 `coupon_scope.type` 的读写完整，并避免每张券单独查询范围造成 N+1 查询：可以按 coupon ID 批量查询后分组，或在券详情读取时一次性装配范围。

## 8. 性能优化的解释口径

### 8.1 优化前后的瓶颈

| 瓶颈 | 优化思路 |
| --- | --- |
| 所有券都进入组合计算 | 订单总价粗筛 + 适用课程金额细筛 |
| 方案逐个串行计算 | 以方案为粒度提交 `CompletableFuture` |
| 规则代码集中在编排类 | 策略模式拆分规则，降低分支复杂度 |
| 同券组不同顺序重复展示 | 对用户券 ID 排序归一化，取同组最大优惠 |
| 课程优惠整数分摊可能少分钱 | 最后一门课程补齐余数 |

### 8.2 为什么自定义线程池

不能直接依赖 `ForkJoinPool.commonPool()` 或无参数 `supplyAsync`，因为公共线程池会和应用中的其他异步任务竞争。独立的 `discountSolutionExecutor` 可以：

- 只承载优惠方案计算；
- 通过线程名前缀定位耗时任务；
- 根据候选方案量和 CPU 核数调节并发度；
- 避免优惠计算拖慢其它异步业务。

并行计算降低的是“多个候选方案计算时间之和”对接口 RT 的影响，不能改变排列算法的阶乘级增长。因此候选券数量必须有业务上限，或继续做以下优化：

- 只生成非空子集的有序排列；
- 先按单券收益做 Top-K；
- 在回溯过程中按当前最优值剪枝；
- 对适用课程集合做位图/BitSet 表示；
- 对完全不重叠的课程范围拆分子问题；
- 直接用 `CompletableFuture.allOf` 汇总并设置超时和降级策略。

### 8.3 800ms 到 100ms 以内的面试表达

可以这样回答：

> 我先把优惠券计算拆成候选过滤、顺序生成、方案试算和最优筛选四个阶段。过滤阶段把无效券挡在组合计算之前；因为不同方案之间没有共享写状态，我使用 CompletableFuture 把每个方案提交到优惠计算专用线程池，并在请求内等待全部结果。最后按优惠金额和用券数量做确定性筛选。压测在固定课程数、候选券数量和并发量下，接口 RT 从约 800ms 降到 100ms 以内。这个方案的边界是排列数量仍可能阶乘增长，所以线上还需要限制候选券数量、处理超时并监控线程池队列。

## 9. 并发安全与异常处理

### 9.1 当前代码为什么基本不会互相覆盖

- `availableCouponMap` 和 `orderCourses` 在任务中只读；
- 每个任务独立创建 `CouponDiscountDTO` 和 `detailMap`；
- 结果列表使用 `Collections.synchronizedList`；
- 策略对象没有实例字段，不保存请求状态；
- `Coupon` 在计算期间没有被修改。

### 9.2 当前实现需要补强的地方

当前代码使用 `CountDownLatch`，但异步链只有 `thenAccept`：

- 如果 `calculateSolutionDiscount` 抛异常，`thenAccept` 不会执行；
- 对应的 `latch.countDown()` 也不会执行；
- 主线程最多等待 1 秒后继续，并可能基于不完整结果筛选；
- 没有记录具体哪个方案失败，也没有取消超时任务；
- `AbortPolicy` 抛出的提交异常也没有统一转换成业务降级。

更稳妥的实现可以用 `handle` 保证每个任务都完成收口，再用 `allOf` 等待：

~~~java
List<CompletableFuture<CouponDiscountDTO>> futures = solutions.stream()
        .map(solution -> CompletableFuture.supplyAsync(
                () -> calculateSolutionDiscount(
                        availableCouponMap, orderCourses, solution),
                discountSolutionExecutor)
                .handle((dto, ex) -> {
                    if (ex != null) {
                        log.error("优惠方案计算失败", ex);
                        return null;
                    }
                    return dto;
                }))
        .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(100, TimeUnit.MILLISECONDS)
        .join();
~~~

生产实现还应过滤 null 结果、恢复中断标记，并明确超时是返回已完成的最好方案还是走无券/单券降级。线程池队列 `99999` 也需要结合实际流量评估，过大的队列可能把瞬时压力转化成内存占用和排队延迟。

## 10. 必测场景

### 功能测试

- 没有未使用优惠券；
- 订单总价低于券门槛；
- 限定分类的课程全部不在订单中；
- 指定课程和指定分类的范围交集；
- 满减、每满减、折扣、无门槛四类公式；
- 最高优惠金额生效；
- 多张券互相重叠和完全不重叠；
- `[A, B]` 与 `[B, A]` 的结果不同；
- 同一组券多个排列取最大优惠；
- 相同比较优惠金额时优先少用券；
- 课程优惠分摊之和等于方案总优惠；
- 最后一门课程补余数后不出现少分钱；
- 下单时重新计算结果与确认页一致。

### 并发和稳定性测试

- 候选券数量从 1、2、3 逐步增加，观察任务数量和 RT；
- 线程池队列接近上限时的降级行为；
- 单个方案抛异常时闭锁是否仍能结束；
- 计算超过超时时间时是否返回可解释结果；
- 促销服务不可用时，交易服务的 Feign fallback 是否按业务要求处理。

## 11. 当前代码基线的关键边界

这一节是面试时最容易被追问的地方：

1. **排列范围**：当前 `PermuteUtil.permute(List<T>)` 只在递归到末尾时加入结果，主流程生成的是“全部候选券的全排列 + 每张单券方案”，并没有生成所有 `2..n-1` 张券的子集排列。若需求是任意多券子集组合，需要把回溯改成“每个非空路径都记录，随后继续选择未使用券”，并控制结果数量。
2. **范围类型**：当前 `DiscountServiceImpl` 的细筛按分类 ID 匹配；`CourseScope` 虽然存在，但没有接入该路径。
3. **数据库查询**：每张限定券都会查询一次 `coupon_scope`，候选券多时存在 N+1 查询，应改为批量查询和按券分组。
4. **异步异常**：当前闭锁等待没有处理 future 异常，可能出现 1 秒后用部分结果返回的情况。
5. **金额类型**：当前金额使用 `int`，业务金额均为分时足够覆盖常规订单，但扩展大额订单或复杂营销规则时应评估改为 `long`。
6. **确定性**：候选券来自 `HashMap.keySet()`，排列起点顺序不保证稳定；如果需要稳定展示或可重复压测，应先按用户券 ID 排序。

如果简历中的“任意多券组合、课程/分类双范围、100ms 以内”来自更完整的后续版本，应明确说明那是演进版本，并把上面几项作为改造点，而不是把当前代码的边界隐藏掉。

## 12. 一分钟总结

这套实现的核心不是某一个优惠公式，而是把“券是否可用、券能作用到哪些课程、券的使用顺序、课程级分摊、最终方案排序”拆开。`DiscountServiceImpl` 负责流程编排，`DiscountStrategy` 负责规则多态，`PermuteUtil` 负责探索顺序，`CompletableFuture + discountSolutionExecutor` 负责并行试算，`CouponDiscountDTO` 负责把结果传回交易服务。这样既能支持多规则扩展，也能把组合计算的串行等待压缩到受线程池控制的并行等待；同时，必须通过候选数量限制、超时收口和范围批量查询控制阶乘复杂度与尾延迟。

## 13. 相关文件索引

- `tj-promotion/src/main/java/com/tianji/promotion/service/impl/DiscountServiceImpl.java`
- `tj-promotion/src/main/java/com/tianji/promotion/utils/PermuteUtil.java`
- `tj-promotion/src/main/java/com/tianji/promotion/config/PromotionConfig.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/Discount.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/DiscountStrategy.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/NoThresholdDiscount.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/PriceDiscount.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/PerPriceDiscount.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/discount/RateDiscount.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/scope/Scope.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/scope/CategoryScope.java`
- `tj-promotion/src/main/java/com/tianji/promotion/strategy/scope/CourseScope.java`
- `tj-promotion/src/main/resources/mapper/UserCouponMapper.xml`
- `tj-trade/src/main/java/com/tianji/trade/service/impl/OrderServiceImpl.java`

> 本文只新增面试走读文档，没有修改优惠券业务代码，也没有改动仓库中已有的中文文件。
