package com.fitmind.learning.constants;

/**
 * 学习服务错误提示。
 */
public class LearningErrorInfo {

    public static class Msg {

        public static final String COURSE_NOT_EXISTS = "课程不存在，无法报名";

        public static final String COURSE_NOT_SHELF = "课程未上架，无法报名";

        public static final String COURSE_PAID = "该课程为付费课程，购买入口将在 M4 开放";

        public static final String ALREADY_ENROLLED = "已报名该课程，无需重复报名";

        public static final String LESSON_NOT_EXISTS = "尚未报名该课程";

        public static final String LESSON_EXPIRED = "该课程已过期，无法继续学习";

        public static final String WEEK_FREQ_ILLEGAL = "每周训练频率需在 1~7 之间";

        public static final String RECORD_SAVE_FAILED = "学习进度保存失败，请稍后重试";
    }
}
