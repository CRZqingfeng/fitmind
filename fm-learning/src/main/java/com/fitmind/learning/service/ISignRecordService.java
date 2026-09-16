package com.fitmind.learning.service;

import com.fitmind.learning.domain.vo.SignRecordVO;
import com.fitmind.learning.domain.vo.SignResultVO;

/**
 * 签到服务。
 *
 * <p>存储用 Redis BitMap：一个用户一个月只占 4 字节左右（31 bit），
 * 若用 MySQL 一天一行，100 万用户每月就是 3100 万行。</p>
 */
public interface ISignRecordService {

    /**
     * 每日签到。
     *
     * @return 连续天数与奖励积分
     */
    SignResultVO sign();

    /** 查询本月签到记录（位图还原） */
    SignRecordVO querySignRecords();
}
