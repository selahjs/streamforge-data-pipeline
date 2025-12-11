package com.example.rest_service.controller;

import com.example.rest_service.service.CsvUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.example.rest_service.service.CsvUploadService.*;

@RestController
@RequestMapping("/api/uploads")
public class CsvUploadController {

  private final CsvUploadService csvUploadService;

  public CsvUploadController(CsvUploadService csvUploadService) {
    this.csvUploadService = csvUploadService;
  }

  /**
   * Endpoint to initiate a large file upload. Returns 202 ACCEPTED immediately.
   * The upload and processing uses asynchronous method by default inorder to prevent usage locks
   * even if the file size is smaller (<1MB), we assume the processing and validation takes time.
   *
   */
  @PostMapping
  public ResponseEntity<String> uploadItems(
          @RequestParam("file") MultipartFile file,
          @RequestParam(value="mode", defaultValue="CHUNK_COMMIT") Mode mode) {

    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("File must not be empty.");
    }
    // 2. Check file type
    if (!"text/csv".equals(file.getContentType())) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Invalid file type. Only CSV is allowed.");
    }

    try {
      // Service returns a Job ID immediately, as the work is offloaded
      String jobId = csvUploadService.handleUpload(file, mode);

      // 202 Accepted status for asynchronous processing
      return ResponseEntity.status(HttpStatus.ACCEPTED)
              .body("Upload job accepted. Status ID: " + jobId +
                      ". Check status at /api/uploads/status?jobId=" + jobId);

    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
              .body("Upload initiation failed: " + e.getMessage());
    }
  }

  /**
   * Endpoint to check the progress and final status of a background job.
   */
  @GetMapping("/status")
  public ResponseEntity<Status> getUploadStatus(@RequestParam String jobId) {
    Status status = csvUploadService.getStatus(jobId);

    if ("NOT_FOUND".equals(status.step())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(status);
    }

    return ResponseEntity.ok(status);
  }
}