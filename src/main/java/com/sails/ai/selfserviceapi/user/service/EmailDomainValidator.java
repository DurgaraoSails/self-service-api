package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.user.exception.InvalidEmailDomainException;
import org.springframework.stereotype.Component;

@Component
public class EmailDomainValidator {

    private final MxRecordLookup mxRecordLookup;

    public EmailDomainValidator(MxRecordLookup mxRecordLookup) {
        this.mxRecordLookup = mxRecordLookup;
    }

    public void validate(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1);
        if (!mxRecordLookup.hasMxRecords(domain)) {
            throw new InvalidEmailDomainException();
        }
    }
}
