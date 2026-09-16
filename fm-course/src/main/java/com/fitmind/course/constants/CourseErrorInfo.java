package com.fitmind.course.constants;

/**
 * 课程模块错误提示。
 */
public class CourseErrorInfo {

    private CourseErrorInfo() {
    }

    /** 错误提示 */
    public static class Msg {
        private Msg() {
        }

        public static final String COURSE_NOT_EXISTS = "课程不存在";
        public static final String COURSE_NAME_EXISTS = "课程名称已存在";
        public static final String COURSE_SAVE_FAILED = "课程保存失败";
        public static final String COURSE_DELETE_FAILED = "课程删除失败";

        public static final String COURSE_UP_SHELF_ALREADY = "课程已上架，请勿重复操作";
        public static final String COURSE_UP_SHELF_NOT_FOUND = "课程不存在，无法上架";
        public static final String COURSE_UP_SHELF_INCOMPLETE = "课程信息不完整，请完成全部填写步骤后再上架";
        public static final String COURSE_UP_SHELF_STATE_WRONG = "只有待上架或已下架的课程才能上架";
        public static final String COURSE_UP_SHELF_ENROLL_ILLEGAL = "报名结束时间不能早于当前时间";
        public static final String COURSE_UP_SHELF_LOCKED = "课程正在处理中，请稍后再试";

        public static final String COURSE_DOWN_SHELF_FAILED = "只有已上架的课程才能下架";

        public static final String CATEGORY_NOT_EXISTS = "分类不存在";
        public static final String CATEGORY_NAME_EXISTS = "分类名称已存在";
        public static final String CATEGORY_HAS_CHILDREN = "该分类下还有子分类，不能删除";
        public static final String CATEGORY_IN_USE = "该分类下还有课程，不能删除";
        public static final String CATEGORY_LEVEL_ILLEGAL = "分类层级不合法，只支持三级";

        // ==================== 训练目录 ====================
        public static final String CATAS_EMPTY = "课程还没有配置训练目录";
        public static final String CATAS_INDEX_REPEAT = "训练日序号重复，请检查后重试";
        public static final String CATAS_INDEX_INTERRUPTED = "训练日序号不连续，请从 1 开始连续编号";
        public static final String CATAS_CHAPTER_WITHOUT_ACTION = "训练日【%s】下面还没有训练动作";
        public static final String CATAS_CHAPTER_DELETED = "训练日【%s】已上架，不能删除";
        public static final String CATAS_CHAPTER_INDEX_LOCKED = "训练日【%s】已上架，不能修改序号";
        public static final String CATAS_ACTION_WITHOUT_MEDIA = "训练动作【%s】尚未上传训练视频";

        // ==================== 训练动作 ====================
        public static final String MEDIA_SAVE_BEFORE_CATAS = "请先保存训练目录，再上传动作视频";
        public static final String MEDIA_CATA_NOT_IN_COURSE = "存在不属于当前课程的训练动作";
        public static final String MEDIA_NOT_EXISTS = "训练视频不存在（媒资id=%s），请重新上传";

        // ==================== 课程教练 ====================
        public static final String TEACHER_SAVE_BEFORE_COURSE = "课程不存在，请先保存课程基本信息";
        public static final String TEACHER_EMPTY = "请至少选择一位课程教练";

        // ==================== 动作小测（题目） ====================
        public static final String SUBJECT_SAVE_BEFORE_COURSE = "课程不存在，请先保存课程基本信息";
        public static final String SUBJECT_NOT_EXISTS = "题目不存在";
        public static final String SUBJECT_TYPE_ILLEGAL = "题目类型只能是 1-单选 2-多选 3-不定向 4-判断";
        public static final String SUBJECT_DIFFICULTY_ILLEGAL = "题目难度只能是 1-简单 2-中等 3-困难";
        public static final String SUBJECT_NAME_TOO_LONG = "题干长度不能超过 200 字";
        public static final String SUBJECT_OPTIONS_ILLEGAL = "选择题的选项需要 2~10 个，且不能为空";
        public static final String SUBJECT_ANSWER_EMPTY = "请设置正确答案";
        public static final String SUBJECT_ANSWER_ILLEGAL = "答案格式不合法，只能填选项序号（从 1 开始）";
        public static final String SUBJECT_ANSWER_OUT_OF_RANGE = "正确答案超出选项范围（选项只有 %s 个）";
        public static final String SUBJECT_JUDGE_ANSWER_ILLEGAL = "判断题的答案只能是 1（正确）或 2（错误）";
        public static final String SUBJECT_SINGLE_ANSWER_MULTIPLE = "单选题只能有一个正确答案";
        public static final String SUBJECT_ANALYSIS_TOO_LONG = "答案解析不能超过 500 字";
        public static final String SUBJECT_IN_USE = "题目【%s】已被课程引用，请先解除关联再删除";
        public static final String CATA_SUBJECT_NOT_IN_COURSE = "存在不属于当前课程的训练节点";
        public static final String CATA_SUBJECT_TYPE_ILLEGAL = "题目只能挂在训练动作或测评节点上";
        public static final String CATA_SUBJECT_REPEAT = "同一个训练节点不能重复提交";
        public static final String CATA_SUBJECT_SUBJECT_NOT_IN_COURSE = "存在不属于当前课程的题目";
        public static final String CATA_SUBJECT_EMPTY = "该训练动作还没有配置小测题目";
        public static final String PRACTICE_ANSWERS_EMPTY = "作答内容不能为空";
    }
}
