package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class PocServiceTest {

    private PocRepository pocRepository;
    private PocService pocService;

    @BeforeEach
    void setUp() {
        pocRepository = Mockito.mock(PocRepository.class);
        pocService = new PocService(pocRepository);
    }

    private static Poc pocWithId(Long id) {
        Poc poc = new Poc();
        poc.setId(id);
        poc.setName("Contract Agent");
        poc.setDescription("Review & generate contracts.");
        return poc;
    }

    @Test
    void listAllReturnsEveryPoc() {
        List<Poc> pocs = List.of(pocWithId(1L), pocWithId(2L));
        when(pocRepository.findAll()).thenReturn(pocs);

        assertThat(pocService.listAll()).isEqualTo(pocs);
    }

    @Test
    void getByIdReturnsTheMatchingPoc() {
        Poc poc = pocWithId(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));

        assertThat(pocService.getById(1L)).isEqualTo(poc);
    }

    @Test
    void getByIdThrowsNotFoundWhenMissing() {
        when(pocRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pocService.getById(99L))
                .isInstanceOf(PocNotFoundException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static PocFields fullFields() {
        return new PocFields(
                "RAG Assistant",
                "Enterprise retrieval-augmented generation assistant.",
                "https://cdn.example.com/icon.svg",
                "https://rag-assistant.example.com",
                "https://github.com/example-org/rag-assistant",
                "1.2.0",
                "AI Team",
                "Generative AI",
                List.of("Python", "FastAPI", "PostgreSQL", "LLM"),
                "registry/company/rag-assistant:1.2.0",
                "interactive",
                "ACTIVE",
                "Longer-form details shown on the public details page.",
                List.of("Step one.", "Step two.")
        );
    }

    @Test
    void createSavesANewPocWithAllFields() {
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Poc created = pocService.create(fullFields());

        assertThat(created.getName()).isEqualTo("RAG Assistant");
        assertThat(created.getDescription()).isEqualTo("Enterprise retrieval-augmented generation assistant.");
        assertThat(created.getIconUrl()).isEqualTo("https://cdn.example.com/icon.svg");
        assertThat(created.getAppUrl()).isEqualTo("https://rag-assistant.example.com");
        assertThat(created.getGithubUrl()).isEqualTo("https://github.com/example-org/rag-assistant");
        assertThat(created.getVersion()).isEqualTo("1.2.0");
        assertThat(created.getOwner()).isEqualTo("AI Team");
        assertThat(created.getCategory()).isEqualTo("Generative AI");
        assertThat(created.getTechnologies()).containsExactly("Python", "FastAPI", "PostgreSQL", "LLM");
        assertThat(created.getContainerImage()).isEqualTo("registry/company/rag-assistant:1.2.0");
        assertThat(created.getDemoType()).isEqualTo("interactive");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getDetails()).isEqualTo("Longer-form details shown on the public details page.");
        assertThat(created.getGuideSteps()).containsExactly("Step one.", "Step two.");
    }

    @Test
    void createDefaultsStatusToActiveAndListsToEmptyWhenOmitted() {
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Poc created = pocService.create(new PocFields(
                "Contract Agent", "Review & generate contracts.", null, null, null,
                null, null, null, null, null, null, null, null, null
        ));

        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getTechnologies()).isEmpty();
        assertThat(created.getGuideSteps()).isEmpty();
    }

    @Test
    void updateReplacesAllFieldsOnTheExistingPoc() {
        Poc existing = pocWithId(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Poc updated = pocService.update(1L, new PocFields(
                "Renamed Agent",
                "New description.",
                "https://cdn.example.com/new-icon.svg",
                "https://renamed.example.com",
                "https://github.com/example-org/renamed",
                "2.0.0",
                "Platform Team",
                "Automation",
                List.of("Java", "Spring Boot"),
                "registry/company/renamed:2.0.0",
                "video",
                "INACTIVE",
                "Updated details.",
                List.of("Only step.")
        ));

        assertThat(updated.getName()).isEqualTo("Renamed Agent");
        assertThat(updated.getDescription()).isEqualTo("New description.");
        assertThat(updated.getIconUrl()).isEqualTo("https://cdn.example.com/new-icon.svg");
        assertThat(updated.getAppUrl()).isEqualTo("https://renamed.example.com");
        assertThat(updated.getGithubUrl()).isEqualTo("https://github.com/example-org/renamed");
        assertThat(updated.getVersion()).isEqualTo("2.0.0");
        assertThat(updated.getOwner()).isEqualTo("Platform Team");
        assertThat(updated.getCategory()).isEqualTo("Automation");
        assertThat(updated.getTechnologies()).containsExactly("Java", "Spring Boot");
        assertThat(updated.getContainerImage()).isEqualTo("registry/company/renamed:2.0.0");
        assertThat(updated.getDemoType()).isEqualTo("video");
        assertThat(updated.getStatus()).isEqualTo("INACTIVE");
        assertThat(updated.getDetails()).isEqualTo("Updated details.");
        assertThat(updated.getGuideSteps()).containsExactly("Only step.");
    }

    @Test
    void updateThrowsWhenMissing() {
        when(pocRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pocService.update(99L, new PocFields(
                "n", "d", null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(PocNotFoundException.class);
    }

    @Test
    void deleteRemovesAnExistingPoc() {
        when(pocRepository.existsById(1L)).thenReturn(true);

        pocService.delete(1L);

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(pocRepository).deleteById(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(1L);
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(pocRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> pocService.delete(99L))
                .isInstanceOf(PocNotFoundException.class);
    }
}
