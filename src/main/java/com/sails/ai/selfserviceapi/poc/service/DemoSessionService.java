package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.DemoSession;
import com.sails.ai.selfserviceapi.poc.repository.DemoSessionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DemoSessionService {
    private final DemoSessionRepository demoSessionRepository;

    public DemoSession save(DemoSession session) {
        return demoSessionRepository.save(session);
    }

    public DemoSession getById(UUID id) {
        return demoSessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo session not found: " + id));
    }
}
