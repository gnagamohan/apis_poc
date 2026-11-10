package com.example.todo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TodoRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        Boolean completed) {
}
