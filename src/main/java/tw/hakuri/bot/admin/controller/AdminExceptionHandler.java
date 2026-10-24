package tw.hakuri.bot.admin.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(basePackageClasses = AdminController.class)
public class AdminExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }

    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException error) { return Map.of("message", error.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException error) { return Map.of("message", error.getMessage()); }

    @ExceptionHandler(DataAccessException.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> storage(DataAccessException error) { return Map.of("message", "資料庫讀寫失敗，請查看程式日誌"); }
}
