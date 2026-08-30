package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.DemoSession;
import com.sails.ai.selfserviceapi.poc.repository.DemoSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoSessionService {
    private final DemoSessionRepository demoSessionRepository;

    public DemoSession save(DemoSession session) {
        return demoSessionRepository.save(session);
    }
}
