package com.example.todo.service;

import com.example.todo.api.TodoRequest;
import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** All operations are scoped to the owner's email; other users' todos behave as not found. */
@Service
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository repository;

    public TodoService(TodoRepository repository) {
        this.repository = repository;
    }

    public List<Todo> findAll(String ownerEmail, Boolean completed) {
        return completed == null
                ? repository.findByOwnerEmailOrderByIdAsc(ownerEmail)
                : repository.findByOwnerEmailAndCompletedOrderByIdAsc(ownerEmail, completed);
    }

    public Todo get(String ownerEmail, long id) {
        return repository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo " + id + " not found"));
    }

    @Transactional
    public Todo create(String ownerEmail, TodoRequest request) {
        Todo todo = new Todo();
        todo.setOwnerEmail(ownerEmail);
        apply(todo, request);
        return repository.save(todo);
    }

    @Transactional
    public Todo update(String ownerEmail, long id, TodoRequest request) {
        Todo todo = get(ownerEmail, id);
        apply(todo, request);
        return repository.saveAndFlush(todo);
    }

    @Transactional
    public void delete(String ownerEmail, long id) {
        repository.delete(get(ownerEmail, id));
    }

    private static void apply(Todo todo, TodoRequest request) {
        todo.setTitle(request.title().strip());
        todo.setDescription(request.description());
        todo.setCompleted(Boolean.TRUE.equals(request.completed()));
    }
}
