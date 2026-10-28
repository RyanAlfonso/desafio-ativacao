package com.example.activation.persistence;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.RequestStatus;
import org.springframework.data.jpa.domain.Specification;

public final class ActivationRequestSpecifications {

    private ActivationRequestSpecifications() {
    }

    public static Specification<ActivationRequest> ownedBy(String clientId) {
        return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
    }

    public static Specification<ActivationRequest> hasStatus(RequestStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ActivationRequest> hasCustomer(String customerId) {
        return customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }
}
