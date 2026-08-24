package com.sails.ai.selfserviceapi.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.user.exception.InvalidEmailDomainException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EmailDomainValidatorTest {

    private final MxRecordLookup mxRecordLookup = Mockito.mock(MxRecordLookup.class);
    private final EmailDomainValidator validator = new EmailDomainValidator(mxRecordLookup);

    @Test
    void passesWhenTheDomainHasMxRecords() {
        when(mxRecordLookup.hasMxRecords("acme.com")).thenReturn(true);

        validator.validate("jane.doe@acme.com");

        verify(mxRecordLookup).hasMxRecords("acme.com");
    }

    @Test
    void throwsWhenTheDomainHasNoMxRecords() {
        when(mxRecordLookup.hasMxRecords("thisdomaindoesnotexist12345.com")).thenReturn(false);

        assertThatThrownBy(() -> validator.validate("jane.doe@thisdomaindoesnotexist12345.com"))
                .isInstanceOf(InvalidEmailDomainException.class)
                .extracting(ex -> ((InvalidEmailDomainException) ex).getCode())
                .isEqualTo("INVALID_EMAIL_DOMAIN");
    }
}
