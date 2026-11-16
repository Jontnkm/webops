package com.webops.server.scan;

/** 검사할 수 없는 주소일 때. 메시지는 화면에 그대로 보여 준다. */
public class InvalidTargetException extends RuntimeException {

    public InvalidTargetException(String message) {
        super(message);
    }
}
