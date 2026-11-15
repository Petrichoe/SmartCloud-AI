package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.MqConsumeRecord;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.MqConsumeRecordMapper;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private static final String POINTS_CONSUMER_NAME = "learning-points";
    private static final DefaultRedisScript<Long> SIGN_POINTS_BOARD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return 0
            end
            redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[3])
            redis.call('SADD', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MqConsumeRecordMapper consumeRecordMapper;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType type) {
        LocalDateTime now = LocalDateTime.now();
        int maxPoints = type.getMaxPoints();
        // 1.判断当前方式有没有积分上限
        int realPoints = points;
        if(maxPoints > 0) {
            // 2.有，则需要判断是否超过上限
            LocalDateTime begin = DateUtils.getDayStartTime(now);
            LocalDateTime end = DateUtils.getDayEndTime(now);
            // 2.1.查询今日已得积分
            int currentPoints = queryUserPointsByTypeAndDate(userId, type, begin, end);
            // 2.2.判断是否超过上限
            if(currentPoints >= maxPoints) {
                // 2.3.超过，直接结束
                return;
            }
            // 2.4.没超过，保存积分记录
            if(currentPoints + points > maxPoints){
                realPoints = maxPoints - currentPoints;
            }
        }
        // 3.没有，直接保存积分记录
        PointsRecord p = new PointsRecord();
        p.setPoints(realPoints);
        p.setUserId(userId);
        p.setType(type);
        save(p);
        // 4.更新总积分到Redis
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);//将积分累加到 Redis ZSet: boards:yyyyMM
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSignPointsRecord(Long userId, int points, LocalDate signDate, String eventId) {
        if (userId == null || signDate == null || !StringUtils.hasText(eventId)) {
            throw new IllegalArgumentException("签到积分事件信息不能为空");
        }
        String expectedEventId = SignInMessage.buildEventId(userId, signDate);
        if (!expectedEventId.equals(eventId)) {
            throw new IllegalArgumentException("签到积分事件键与业务数据不匹配");
        }

        // 消费记录与积分明细在同一 MySQL 事务中提交，唯一键负责并发去重。
        MqConsumeRecord consumeRecord = new MqConsumeRecord();
        consumeRecord.setConsumerName(POINTS_CONSUMER_NAME);
        consumeRecord.setBusinessKey(eventId);
        if (consumeRecordMapper.insert(consumeRecord) != 1) {
            throw new IllegalStateException("保存 MQ 消费记录失败");
        }

        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setPoints(points);
        record.setType(PointsRecordType.SIGN);
        record.setCreateTime(signDate.atStartOfDay());
        if (!save(record)) {
            throw new IllegalStateException("保存签到积分明细失败");
        }

        // Redis 用事件键原子去重，避免命令已执行但响应丢失后，消费重试重复加分。
        String season = signDate.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        String eventSetKey = RedisConstants.POINTS_BOARD_SIGN_EVENT_KEY_PREFIX + season;
        String boardKey = RedisConstants.POINTS_BOARD_KEY_PREFIX + season;
        redisTemplate.execute(SIGN_POINTS_BOARD_SCRIPT, List.of(eventSetKey, boardKey),
                eventId, Integer.toString(points), userId.toString());
    }

    @Override
    public boolean isSignPointsProcessed(String eventId) {
        QueryWrapper<MqConsumeRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(MqConsumeRecord::getConsumerName, POINTS_CONSUMER_NAME)
                .eq(MqConsumeRecord::getBusinessKey, eventId);
        return consumeRecordMapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        // 1.获取用户
        Long userId = UserContext.getUser();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);
        // 3.构建查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .between(PointsRecord::getCreateTime, begin, end);
        // 4.查询
        List<PointsRecord> list = getBaseMapper().queryUserPointsByDate(wrapper);
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        // 5.封装返回
        List<PointsStatisticsVO> vos = new ArrayList<>(list.size());
        for (PointsRecord p : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(p.getType().getDesc());
            vo.setMaxPoints(p.getType().getMaxPoints());
            vo.setPoints(p.getPoints());
            vos.add(vo);
        }
        return vos;
    }

    private int queryUserPointsByTypeAndDate(
            Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        // 1.查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(type != null, PointsRecord::getType, type)
                .between(begin != null && end != null, PointsRecord::getCreateTime, begin, end);
        // 2.调用mapper，查询结果
        Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);
        // 3.判断并返回
        return points == null ? 0 : points;
    }
}
