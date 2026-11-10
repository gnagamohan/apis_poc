package com.example.todo.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByOwnerEmailOrderByIdAsc(String ownerEmail);

    List<Todo> findByOwnerEmailAndCompletedOrderByIdAsc(String ownerEmail, boolean completed);

    Optional<Todo> findByIdAndOwnerEmail(Long id, String ownerEmail);
}
