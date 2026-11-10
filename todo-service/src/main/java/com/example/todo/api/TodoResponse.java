package com.example.todo.api;

import com.example.todo.domain.Todo;

import java.time.Instant;

public record TodoResponse(
        Long id,
        String ownerEmail,
        String title,
        String description,
        boolean completed,
        Instant createdAt,
        Instant updatedAt) {

    public static TodoResponse from(Todo t) {
        return new TodoResponse(t.getId(), t.getOwnerEmail(), t.getTitle(), t.getDescription(),
                t.isCompleted(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
