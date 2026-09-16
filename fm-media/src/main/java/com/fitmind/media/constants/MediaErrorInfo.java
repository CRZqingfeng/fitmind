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
    }
}
