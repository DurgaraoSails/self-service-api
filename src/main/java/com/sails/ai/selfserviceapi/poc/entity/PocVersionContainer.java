package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

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

    @Column(name = "name", nullable = false, updatable = false, length = 40)
    private String name;

    /** {@code "INGRESS"} or {@code "SIDECAR"} — matches {@code ContainerRole} in the deploy pipeline. */
    @Column(name = "role", nullable = false, updatable = false, length = 16)
    private String role;

    @Column(name = "container_image")
    private String containerImage;

    @Column(name = "port")
    private Integer port;
}
