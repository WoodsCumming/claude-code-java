package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * AWS/cloud auth status manager.
 * Translated from src/utils/awsAuthStatusManager.ts
 *
 * Manages authentication status for cloud providers (AWS Bedrock, GCP Vertex).
 */
@Slf4j
@Component
public class AwsAuthStatusManager {



    private static volatile AwsAuthStatusManager instance;

    private volatile AwsAuthStatus status = new AwsAuthStatus(false, new ArrayList<>(), null);
    private final List<Consumer<AwsAuthStatus>> listeners = new CopyOnWriteArrayList<>();

    public static AwsAuthStatusManager getInstance() {
        if (instance == null) {
            instance = new AwsAuthStatusManager();
        }
        return instance;
    }

    public AwsAuthStatus getStatus() {
        return new AwsAuthStatus(
            status.isAuthenticating(),
            new ArrayList<>(status.getOutput()),
            status.getError()
        );
    }

    public void startAuthentication() {
        status = new AwsAuthStatus(true, new ArrayList<>(), null);
        notifyListeners();
    }

    public void addOutput(String line) {
        List<String> newOutput = new ArrayList<>(status.getOutput());
        newOutput.add(line);
        status = new AwsAuthStatus(status.isAuthenticating(), newOutput, status.getError());
        notifyListeners();
    }

    public void completeAuthentication() {
        status = new AwsAuthStatus(false, status.getOutput(), null);
        notifyListeners();
    }

    public void failAuthentication(String error) {
        status = new AwsAuthStatus(false, status.getOutput(), error);
        notifyListeners();
    }

    public Runnable subscribe(Consumer<AwsAuthStatus> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        AwsAuthStatus current = getStatus();
        for (Consumer<AwsAuthStatus> listener : listeners) {
            try {
                listener.accept(current);
            } catch (Exception e) {
                log.warn("Auth status listener failed: {}", e.getMessage());
            }
        }
    }

    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class AwsAuthStatus {
        private final boolean authenticating;
        private final List<String> output;
        private final String error;

        public boolean isAuthenticating() { return authenticating; }
    }
}
