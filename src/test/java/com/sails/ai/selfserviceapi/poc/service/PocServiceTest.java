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

    @Test
    void createSavesANewPocWithAllFields() {
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Poc created = pocService.create(
                "Contract Agent",
                "Review & generate contracts.",
                "https://cdn.example.com/icon.svg",
                "https://contract-agent.example.com",
                "https://github.com/example-org/contract-agent"
        );

        assertThat(created.getName()).isEqualTo("Contract Agent");
        assertThat(created.getDescription()).isEqualTo("Review & generate contracts.");
        assertThat(created.getIconUrl()).isEqualTo("https://cdn.example.com/icon.svg");
        assertThat(created.getAppUrl()).isEqualTo("https://contract-agent.example.com");
        assertThat(created.getGithubUrl()).isEqualTo("https://github.com/example-org/contract-agent");
    }

    @Test
    void updateReplacesAllFieldsOnTheExistingPoc() {
        Poc existing = pocWithId(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Poc updated = pocService.update(
                1L,
                "Renamed Agent",
                "New description.",
                "https://cdn.example.com/new-icon.svg",
                "https://renamed.example.com",
                "https://github.com/example-org/renamed"
        );

        assertThat(updated.getName()).isEqualTo("Renamed Agent");
        assertThat(updated.getDescription()).isEqualTo("New description.");
        assertThat(updated.getIconUrl()).isEqualTo("https://cdn.example.com/new-icon.svg");
        assertThat(updated.getAppUrl()).isEqualTo("https://renamed.example.com");
        assertThat(updated.getGithubUrl()).isEqualTo("https://github.com/example-org/renamed");
    }

    @Test
    void updateThrowsWhenMissing() {
        when(pocRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pocService.update(99L, "n", "d", null, null, null))
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
