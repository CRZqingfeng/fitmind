package com.fitmind.exam.constants;

/**
 * 体测模块错误提示。
 */
public class ExamErrorInfo {

    private ExamErrorInfo() {
    }

    public static class Msg {
        private Msg() {
        }
        // ==================== 体测 ====================
        public static final String BODY_PARAM_ILLEGAL = "体测数据不完整或不合法：%s";
        public static final String BODY_NOT_EXISTS = "体测记录不存在";
        public static final String BODY_NOT_MINE = "只能查看自己的体测记录";
        public static final String BODY_NO_HISTORY = "还没有体测记录，先做一次体测吧";
        // ==================== 动作考核 ====================
        public static final String ACTION_NOT_EXISTS = "考核记录不存在";
        public static final String ACTION_NOT_MINE = "只能查看自己的考核记录";
        public static final String ACTION_REVIEWED = "该考核已评分，不能重复评分";
        public static final String ACTION_SCORE_ILLEGAL = "评分必须在 0~100 之间";
        public static final String ACTION_VIDEO_REQUIRED = "请先上传动作视频";
        public static final String NOT_COACH = "只有教练才能评分";
    }
}
