package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException e,
                                                             HttpServletRequest req) {
        String rootMsg = e.getMostSpecificCause() != null
            ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.info("400 BadJson {} {} cause={}", req.getMethod(), req.getRequestURI(), rootMsg);
        return ResponseEntity.badRequest().body(body(400, "INVALID_JSON",
            "请求体不是合法 JSON：字段名需用双引号包裹、字符串内的反斜杠需正确转义",
            rootMsg, req));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e,
                                                            HttpServletRequest req) {
        log.info("405 MethodNotAllowed {} {} supported={}",
            req.getMethod(), req.getRequestURI(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body(405, "METHOD_NOT_ALLOWED",
            "该接口不支持 " + req.getMethod() + " 方法，支持：" + e.getSupportedHttpMethods(),
            e.getMessage(), req));
    }

    private Map<String, Object> body(int status, String code, String message, String detail,
                                     HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("code", code);
        m.put("message", message);
        m.put("detail", detail);
        m.put("path", req.getRequestURI());
        m.put("method", req.getMethod());
        return m;
    }
}
