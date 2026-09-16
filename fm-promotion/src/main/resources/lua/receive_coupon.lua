-- ============================================================
-- ★ 秒杀领券：库存 / 有效期 / 每人限领，一次原子执行
-- ------------------------------------------------------------
-- KEYS[1] = 券缓存 key（prs:coupon:{couponId}）
--           Hash: totalNum 剩余库存 / issueEndTime 领取截止 / userLimit 每人限领
-- KEYS[2] = 用户领券计数 key（prs:user:coupon:{couponId}）Hash: field=userId
-- ARGV[1] = userId
--
-- 返回值：0-成功 1-券不存在 2-已领完 3-已过期 4-超过每人限领
--
-- ★★ 为什么必须写成一个 Lua 脚本
--   这三步判断（还有扣库存）如果拆成多次 Redis 调用，就会有竞态：
--   "判断还有库存" 和 "扣减库存" 之间，可能已经有几十个人也判断通过了 —— 直接超卖。
--   Redis 执行 Lua 是单线程的，整个脚本相当于一个原子操作，中间不会被插入别人的命令。
--
-- ⚠️ 已知取舍（与常见实现一致）：限领判断用 HINCRBY 先自增再比较，
--    当超过限领时计数已加过了、不会回滚。后果是计数偏大（只会更严格，不会放宽），
--    而且拒绝之后用户看到的是明确的"已经领过了"，不影响正确性。
-- ============================================================

-- 1. 券是否存在
if (redis.call('exists', KEYS[1]) == 0) then
    return 1
end

-- 2. 库存是否还有
if (tonumber(redis.call('hget', KEYS[1], 'totalNum')) <= 0) then
    return 2
end

-- 3. 是否还在领取时间内
if (tonumber(redis.call('time')[1]) > tonumber(redis.call('hget', KEYS[1], 'issueEndTime'))) then
    return 3
end

-- 4. 是否超过每人限领（先自增再比较，见上方说明）
if (tonumber(redis.call('hget', KEYS[1], 'userLimit')) < redis.call('hincrby', KEYS[2], ARGV[1], 1)) then
    return 4
end

-- 5. 扣减库存
redis.call('hincrby', KEYS[1], "totalNum", "-1")
return 0
