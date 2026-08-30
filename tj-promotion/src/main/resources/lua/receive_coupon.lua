if(redis.call('exists', KEYS[1]) == 0) then  --判断优惠券缓存是否存在，不存在说明未开始或已结束。
    return 1
end
if(tonumber(redis.call('hget', KEYS[1], 'totalNum')) <= 0) then  --判断 totalNum 是否大于 0。
    return 2
end
if(tonumber(redis.call('time')[1]) > tonumber(redis.call('hget', KEYS[1], 'issueEndTime'))) then --判断当前 Redis 时间是否超过 issueEndTime。
    return 3
end
if(tonumber(redis.call('hget', KEYS[1], 'userLimit')) < redis.call('hincrby', KEYS[2], ARGV[1], 1)) then  --对当前用户领取数 hincrby +1，再判断是否超过 userLimit。
    return 4
end
redis.call('hincrby', KEYS[1], "totalNum", "-1")  --全部通过后，把 Redis 中 totalNum -1。
return 0  --返回 0 表示成功。


-- 领取优惠券时对于并发问题进行原子处理的Lua代码


