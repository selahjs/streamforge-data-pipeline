package com.example.rest_service.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CsvUploadService
 *
 * Primary role: Manage the state of background upload jobs and delegate processing.
 * Secondary role: Hold utility records and enums used by the BackgroundProcessor.
 */
@Service
public class CsvUploadService {

  // Status Map: Holds the state of all current/recent jobs. Thread-safe map is essential.
  private final Map<String, Status> jobStatus = new ConcurrentHashMap<>();

  private final BackgroundCsvProcessor processor; // Injected async worker

  // Utility records and enums
  public record Status(String step, String message, long processedRows, long totalRows) {}
  public record ValidationSummary(Map<String, Long> errorCounts) {}
  public record UploadResult(long processed, long inserted, long failed, File errorReportFile, ValidationSummary summary) {}
  public enum Mode { ALL_OR_NOTHING, CHUNK_COMMIT }

  public CsvUploadService(BackgroundCsvProcessor processor) {
    this.processor = processor;
  }

  // --- Status Management ---

  /**
   * Public method for the controller to get the status of a specific job.
   */
  public Status getStatus(String jobId) {
    return jobStatus.getOrDefault(jobId, new Status("NOT_FOUND", "Job ID not found or expired.", 0, 0));
  }

  /**
   * Helper to update the status map (used by the BackgroundProcessor).
   */
  public void updateStatus(String jobId, String step, String message, long processedRows, long totalRows) {
    jobStatus.put(jobId, new Status(step, message, processedRows, totalRows));
    System.out.println(String.format("JOB[%s] STATUS: %s - %s", jobId, step, message));
  }

  // Helper function to create a new File instance in a safe location
  private File getSafeStorageFile(String originalFilename) throws IOException {
    // Creates a unique file name in the system's temporary directory
    String tempDir = System.getProperty("java.io.tmpdir");
    String safeFileName = UUID.randomUUID().toString() + "_" + originalFilename;
    System.out.println(tempDir);
    return new File(tempDir, safeFileName);
  }

  /**
   * Public entry point for file upload. Delegates heavy work to the background processor.
   * @return The unique Job ID for status tracking.
   */
  public String handleUpload(MultipartFile file, Mode mode) {
    // 1. Generate unique job ID
    String jobId = UUID.randomUUID().toString();

    // 2. Initialize status map entry
    updateStatus(jobId, "INIT", "Upload received, preparing for background processing.", 0, 0);

    // 3. Delegate work to the @Async component.
    // --- NEW: Copy the file SYNCHRONOUSLY before returning ---
    try {
      File permanentFile = getSafeStorageFile(file.getOriginalFilename());
      file.transferTo(permanentFile); // Copies the temporary upload file to a safe location

      // Pass the safe File object to the async processor
      processor.startProcessing(permanentFile, mode, jobId, jobStatus);

    } catch (IOException e) {
      updateStatus(jobId, "UPLOAD_FAILED", "Failed to transfer file to safe storage: " + e.getMessage(), 0, 0);
      return jobId; // Return job ID with failure status
    }

    // 4. Immediately return the Job ID
    return jobId;
  }
}