package com.smartkpi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = SmartKpiController.class)
public class SmartKpiExceptionHandler {

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleMultipartException(Exception ex) {
        return error(
                HttpStatus.BAD_REQUEST,
                "上传失败，请确认已选择 5 个 Excel 文件，且单文件不超过 50MB、总大小不超过 250MB。"
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "请求参数缺失：" + ex.getParameterName());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "请求处理失败。"
                : ex.getReason();
        return error(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "系统处理失败，请稍后重试。"
                : ex.getMessage();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("taskId", null);
        body.put("bootDownloadUrl", null);
        body.put("resourceDownloadUrl", null);
        return ResponseEntity.status(status).body(body);
    }
}
