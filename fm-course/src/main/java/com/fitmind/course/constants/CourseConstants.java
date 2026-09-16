package com.fitmind.course.constants;

/**
 * 课程模块常量。
 */
public class CourseConstants {

    private CourseConstants() {
    }

    /**
     * 课程填写步骤。
     *
     * <p>支持<b>断点续填</b>：教练今天填到目录就关页面，明天回来接着填。
     * 每完成一步就把 step 推进一格，上架时校验是否走到了最后一步。</p>
     */
    public class CourseStep {

        /** 基本信息：名称、封面、分类、价格 */
        public static final int BASE_INFO = 1;

        /** 训练目录：训练日 / 训练动作 */
        public static final int CATALOGUE = 2;

        /** 视频：给每个动作挂训练视频 */
        public static final int MEDIA = 3;

        /** 习题：动作小测 */
        public static final int SUBJECT = 4;

        /** 教练 */
        public static final int TEACHER = 5;

        /**
         * 上架要求的最低 step。
         *
         * <p>M2-2a 时只实现了基本信息，门槛是 BASE_INFO；
         * <b>M2-2b 补完训练目录与教练后，已改为 TEACHER</b>——
         * 信息不全的课程不能上架。</p>
         *
         * <p>M2-2c 补完动作小测（可选）后，可视情况改为 SUBJECT。</p>
         */
        public static final int REQUIRED_FOR_SHELF = TEACHER;
    }

    /**
     * 目录类型。
     *
     * <p>健身场景下的语义：</p>
     * <pre>
     *   训练日（章）
     *     └── 训练动作（节）
     * </pre>
     */
    public class CataType {

        /** 训练日 */
        public static final int CHAPTER = 1;

        /** 训练动作 */
        public static final int SECTION = 2;

        /** 测试 / 测评 */
        public static final int PRACTICE = 3;
    }
}
