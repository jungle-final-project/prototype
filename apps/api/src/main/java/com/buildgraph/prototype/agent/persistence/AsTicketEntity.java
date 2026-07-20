package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "as_tickets")
public class AsTicketEntity extends PublicIdEntity {
    @Column(name = "diagnosis_id", unique = true)
    private UUID diagnosisId;

    @Column(name = "agent_device_id")
    private Long agentDeviceId;

    @Column(name = "request_number", unique = true)
    private String requestNumber;

    @Column(name = "request_type")
    private String requestType;

    @Column(name = "diagnosis_title")
    private String diagnosisTitle;

    @Column(name = "diagnosis_summary")
    private String diagnosisSummary;

    @Column(name = "evidence_summary", columnDefinition = "jsonb")
    private String evidenceSummary;

    @Column(name = "diagnosed_at")
    private Instant diagnosedAt;

    @Column(name = "diagnosis_mode")
    private String diagnosisMode;

    @Column(name = "diagnosis_result", columnDefinition = "jsonb")
    private String diagnosisResult;

    @Column(name = "diagnosis_consent_accepted_at")
    private Instant diagnosisConsentAcceptedAt;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "log_upload_id")
    private Long logUploadId;

    @Column(name = "assigned_admin_id")
    private Long assignedAdminId;

    @Column(name = "symptom", nullable = false)
    private String symptom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AsTicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AsAnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private AsReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "support_decision")
    private AsSupportDecision supportDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @Column(name = "auto_response_allowed", nullable = false)
    private Boolean autoResponseAllowed;

    @Column(name = "cause_candidates", columnDefinition = "jsonb")
    private String causeCandidates;

    @Column(name = "upgrade_candidates", columnDefinition = "jsonb")
    private String upgradeCandidates;

    @Column(name = "incident_window", columnDefinition = "jsonb")
    private String incidentWindow;

    @Column(name = "log_summary", columnDefinition = "jsonb")
    private String logSummary;

    @Column(name = "support_routing", columnDefinition = "jsonb")
    private String supportRouting;

    @Column(name = "ai_diagnosis_request", columnDefinition = "jsonb")
    private String aiDiagnosisRequest;

    @Column(name = "exception_approval_reason")
    private String exceptionApprovalReason;

    @Column(name = "exception_responsibility_scope")
    private String exceptionResponsibilityScope;

    @Column(name = "exception_user_message")
    private String exceptionUserMessage;

    @Column(name = "exception_approved_at")
    private Instant exceptionApprovedAt;

    @Column(name = "exception_approved_by")
    private Long exceptionApprovedBy;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AsTicketEntity() {
    }
}
