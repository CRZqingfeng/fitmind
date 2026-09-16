package com.fitmind.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.aigc.domain.po.ChatSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper。
 *
 * <p>⚠️ {@code @Mapper} 不能漏：本项目没有全局 {@code @MapperScan}，
 * 靠 MyBatis 按启动类包路径 + 本注解注册。漏了的表现是
 * <b>服务能启动，但注入 Mapper 时失败</b>（{@code No qualifying bean of type ...}）——
 * 这是 M5 建 fm-data 时踩过的坑，已记进排障手册 §3.18。</p>
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
