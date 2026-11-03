package com.fitmind.media.constants;

/**
 * 媒资模块错误提示。
 */
public class MediaErrorInfo {

    private MediaErrorInfo() {
    }

    /** 错误提示 */
    public static class Msg {
        private Msg() {
        }

        public static final String FILE_KEY_IS_NULL = "文件key不能为空";
        public static final String FILE_UPLOAD_ERROR = "上传文件失败";
        public static final String FILE_DELETE_ERROR = "删除文件失败";
        public static final String FILE_NOT_EXISTS = "文件不存在";

        public static final String MEDIA_NOT_EXISTS = "媒资不存在";
        public static final String MEDIA_UPLOAD_ERROR = "上传视频失败";
        public static final String MEDIA_DELETE_ERROR = "删除视频失败";
        public static final String MEDIA_NOT_UPLOADED = "视频尚未上传到对象存储，无法确认";
        public static final String MEDIA_IN_USE = "该视频已被课程引用，不能删除";
        public static final String MEDIA_FILENAME_IS_NULL = "视频文件名不能为空";

        // ==================== M12-1 分片上传 ====================
        public static final String MULTIPART_NOT_SUPPORTED = "当前对象存储后端不支持分片上传，请改用普通上传接口";
        public static final String UPLOAD_TASK_NOT_EXISTS = "上传任务不存在";
        public static final String UPLOAD_TASK_FORBIDDEN = "无权操作该上传任务";
        public static final String UPLOAD_TASK_NOT_UPLOADING = "上传任务不在上传中状态，无法继续";
        public static final String UPLOAD_TASK_COMPLETED = "上传任务已完成，请勿重复提交";
    }
}
