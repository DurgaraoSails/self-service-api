package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

final class UserSpecifications {

    private UserSpecifications() {
    }

    static Specification<User> registeredBetween(Instant from, Instant to) {
        return (root, query, cb) -> cb.between(root.get("createdAt"), from, to);
    }

    static Specification<User> needsAttention(Instant now, Instant cutoff) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("status"), UserStatus.ACTIVE),
                cb.between(root.get("trialEndDate"), now, cutoff)
        );
    }

    /** Case-insensitive substring match across the fields the Customers search box covers. */
    static Specification<User> matchesSearch(String term) {
        String pattern = "%" + term.toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate firstName = cb.like(cb.lower(root.get("firstName")), pattern);
            Predicate lastName = cb.like(cb.lower(root.get("lastName")), pattern);
            Predicate companyName = cb.like(cb.lower(root.get("companyName")), pattern);
            Predicate email = cb.like(cb.lower(root.get("email")), pattern);
            return cb.or(firstName, lastName, companyName, email);
        };
    }
}
