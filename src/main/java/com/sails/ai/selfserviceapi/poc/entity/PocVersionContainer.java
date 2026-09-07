package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One container a version actually built, as declared by the manifest it was built with. Purely
 * additive alongside {@code PocVersion.containerImage}/{@code commitSha}, which keep meaning
 * exactly what they mean today (the ingress image; the repo commit) — a pre-manifest version, or
 * any version whose repo simply had no poc.yaml, simply has zero rows here.
 */
@Entity
@Table(name = "poc_version_containers")
@Getter
@Setter
public class PocVersionContainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "poc_version_id", nullable = false, updatable = false)
    private Long pocVersionId;

    @Column(name = "name", nullable = false, length = 40, updatable = false)
    private String name;

    /** Plain string constant, "INGRESS" or "SIDECAR" — matches PocDeployment.status/.kind's convention. */
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private String role;

    @Column(name = "container_image", length = 500)
    private String containerImage;

    /** Set for a sidecar; null for the ingress container, which binds Cloud Run's own $PORT. */
    @Column(name = "port")
    private Integer port;
}
