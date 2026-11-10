package com.example.todo.api;

import com.example.todo.service.TodoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * The caller's identity comes from X-User-Email, set by the gateway from the verified JWT.
 * This service is only reachable from the gateway (internal Docker network).
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    static final String USER_EMAIL_HEADER = "X-User-Email";

    private final TodoService service;

    public TodoController(TodoService service) {
        this.service = service;
    }

    @GetMapping
    public List<TodoResponse> list(@RequestHeader(value = USER_EMAIL_HEADER, required = false) String email,
                                   @RequestParam(required = false) Boolean completed) {
        return service.findAll(user(email), completed).stream().map(TodoResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TodoResponse get(@RequestHeader(value = USER_EMAIL_HEADER, required = false) String email,
                            @PathVariable long id) {
        return TodoResponse.from(service.get(user(email), id));
    }

    @PostMapping
    public ResponseEntity<TodoResponse> create(@RequestHeader(value = USER_EMAIL_HEADER, required = false) String email,
                                               @Valid @RequestBody TodoRequest request) {
        TodoResponse created = TodoResponse.from(service.create(user(email), request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public TodoResponse update(@RequestHeader(value = USER_EMAIL_HEADER, required = false) String email,
                               @PathVariable long id, @Valid @RequestBody TodoRequest request) {
        return TodoResponse.from(service.update(user(email), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = USER_EMAIL_HEADER, required = false) String email,
                                       @PathVariable long id) {
        service.delete(user(email), id);
        return ResponseEntity.noContent().build();
    }

    private static String user(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        return email.strip();
    }
}
